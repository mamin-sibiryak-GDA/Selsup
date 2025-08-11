package com.example.selsup; // Пакет, в котором находится класс

// Импорты стандартных библиотек Java
import java.io.IOException;
import java.net.URI; // Для работы с адресами
import java.net.http.HttpClient; // HTTP-клиент из Java 11
import java.net.http.HttpRequest; // HTTP-запрос
import java.net.http.HttpResponse; // HTTP-ответ
import java.time.Duration; // Для задания таймаутов
import java.util.Base64; // Для кодирования Base64
import java.util.Objects; // Для удобной проверки на null
import java.util.concurrent.Semaphore; // Для ограничения количества одновременных действий
import java.util.concurrent.ScheduledExecutorService; // Для периодического выполнения задач
import java.util.concurrent.Executors; // Для создания пула потоков
import java.util.concurrent.TimeUnit; // Для работы с единицами времени
import java.util.concurrent.atomic.AtomicInteger; // Для потокобезопасного счётчика

// Импорт Jackson для сериализации объектов в JSON
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * CrptApi — минимальный, расширяемый клиент для работы с API Честного Знака.
 * Ограничивает количество запросов за заданный интервал времени.
 */
public class CrptApi {

    // === Поля конфигурации ===
    private final TimeUnit intervalUnit; // Единица времени, в которой задан интервал
    private final int maxRequestsPerInterval; // Максимум запросов в интервал
    private final Semaphore permits; // Семафор для ограничения запросов
    private final ScheduledExecutorService scheduler; // Планировщик для восстановления лимита
    private final AtomicInteger currentIntervalCounter = new AtomicInteger(0); // Счётчик запросов в текущем интервале

    private final HttpClient httpClient; // HTTP-клиент для отправки запросов
    private final ObjectMapper objectMapper; // JSON-сериализатор

    // Динамические параметры API
    private volatile String baseUrl;     // URL сервера Честного Знака
    private volatile String authToken;   // Токен авторизации
    private volatile String productGroup; // Группа товаров (pg=...)

    private volatile boolean closed = false; // Флаг, что API-клиент закрыт

    /**
     * Конструктор по ТЗ.
     * @param timeUnit единица времени интервала (секунда, минута и т.д.)
     * @param requestLimit максимум запросов в интервал
     */
    public CrptApi(TimeUnit timeUnit, int requestLimit) {
        if (timeUnit == null) throw new IllegalArgumentException("timeUnit == null");
        if (requestLimit <= 0) throw new IllegalArgumentException("requestLimit must be > 0");

        this.intervalUnit = timeUnit;
        this.maxRequestsPerInterval = requestLimit;
        // Семафор с количеством разрешений = лимиту запросов
        this.permits = new Semaphore(requestLimit, true);
        // Планировщик, который будет возвращать разрешения каждые N секунд/минут
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "CrptApi-Refill"); // Поток с именем для отладки
            t.setDaemon(true); // Демон, чтобы не мешал завершению JVM
            return t;
        });

        // HTTP-клиент с таймаутом подключения 20 секунд
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();

        // Jackson для сериализации
        this.objectMapper = new ObjectMapper();

        // Планируем автоматическое восстановление разрешений
        long periodMillis = intervalUnit.toMillis(1);
        scheduler.scheduleAtFixedRate(this::refillPermits, periodMillis, periodMillis, TimeUnit.MILLISECONDS);
    }

    // Конструктор с установкой всех параметров.
    public CrptApi(TimeUnit timeUnit, int requestLimit, String baseUrl, String authToken, String productGroup) {
        this(timeUnit, requestLimit);
        this.baseUrl = baseUrl;
        this.authToken = authToken;
        this.productGroup = productGroup;
    }

    // Установка базового URL
    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    // Установка токена
    public void setAuthToken(String authToken) {
        this.authToken = authToken;
    }

    // Установка группы товаров
    public void setProductGroup(String productGroup) {
        this.productGroup = productGroup;
    }

    // Метод создания документа для ввода в оборот.
    public String createIntroduceGoods(ProductDocument document, String signature) throws InterruptedException, IOException {
        ensureNotClosed(); // Проверка, что клиент не закрыт
        Objects.requireNonNull(document, "document must not be null");

        // Проверяем, что обязательные параметры заданы
        if (baseUrl == null || baseUrl.isBlank())
            throw new IllegalStateException("baseUrl is not set. Use setBaseUrl(...)");
        if (authToken == null || authToken.isBlank())
            throw new IllegalStateException("authToken is not set. Use setAuthToken(...)");
        if (productGroup == null || productGroup.isBlank())
            throw new IllegalStateException("productGroup is not set. Use setProductGroup(...)");

        // Блокируем выполнение, если лимит исчерпан
        permits.acquire();

        try {
            // Формируем JSON-тело запроса
            String requestBody = buildCreateRequestBody(document, signature);

            // Адрес API
            String endpoint = normalizeBaseUrl(baseUrl) + "/api/v3/lk/documents/create?pg=" + urlEncode(productGroup);

            // Создаём HTTP-запрос
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(60)) // Таймаут на выполнение запроса
                    .header("Content-Type", "application/json")
                    .header("Accept", "*/*")
                    .header("Authorization", normalizeBearer(authToken))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody)) // POST с телом
                    .build();

            // Отправляем запрос и получаем ответ
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            // Читаем код ответа и тело
            int sc = response.statusCode();
            String respBody = response.body();

            // Если код 2xx — возвращаем тело
            if (sc >= 200 && sc < 300) {
                return respBody;
            } else {
                throw new IOException("API returned non-2xx status: " + sc + ", body: " + respBody);
            }

        } catch (IOException | RuntimeException e) {
            throw e; // Пробрасываем исключение
        }
    }

    // Восстановление разрешений для семафора
    private void refillPermits() {
        if (closed) return;
        int available = permits.availablePermits();
        if (available < maxRequestsPerInterval) {
            int toRelease = maxRequestsPerInterval - available;
            permits.release(toRelease);
        }
        currentIntervalCounter.set(0);
    }

    // Убирает завершающий "/" у URL
    private static String normalizeBaseUrl(String url) {
        if (url.endsWith("/")) return url.substring(0, url.length() - 1);
        return url;
    }

    // Добавляет "Bearer " к токену, если его нет
    private static String normalizeBearer(String authToken) {
        String t = authToken.trim();
        if (t.toLowerCase().startsWith("bearer ")) return t;
        return "Bearer " + t;
    }

    // Кодирует строку для URL
    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8.toString());
        } catch (Exception e) {
            return s;
        }
    }

    // Проверка, что клиент не закрыт
    private void ensureNotClosed() {
        if (closed) throw new IllegalStateException("CrptApi is closed");
    }

    // Закрытие планировщика
    public void shutdown() {
        closed = true;
        scheduler.shutdownNow();
    }

    // DTO для документа. Пользователь заполняет поля по схеме API.
    public static class ProductDocument {
        public String participantInn; // ИНН участника
        public String productionDate; // Дата производства
        public String usageType; // Тип использования
        // ... другие поля ...

        public ProductDocument() {}

        @Override
        public String toString() {
            return "{participantInn:" + participantInn + ", productionDate:" + productionDate + "}";
        }
    }

    // Формирование тела запроса.
    String buildCreateRequestBody(ProductDocument document, String signature) throws IOException {
        String docJson;
        try {
            docJson = objectMapper.writeValueAsString(document);
        } catch (Exception e) {
            docJson = document.toString();
        }

        String productDocumentBase64 = Base64.getEncoder()
                .encodeToString(docJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        CreateRequestBody body = new CreateRequestBody();
        body.document_format = "MANUAL";
        body.product_document = productDocumentBase64;
        body.signature = signature != null ? signature : "";
        body.type = "LP_INTRODUCE_GOODS";

        return objectMapper.writeValueAsString(body);
    }

    // Внутренний класс для маппинга тела запроса
    private static class CreateRequestBody {
        public String document_format;
        public String product_document;
        public String product_group;
        public String signature;
        public String type;
    }
}

    /*
    // Пример использования:
    CrptApi api = new CrptApi(TimeUnit.MINUTES, 10);
    api.setBaseUrl("https://ismp.crpt.ru");
    api.setAuthToken("eyJ..."); // токен (без "Bearer " или с ним)
    api.setProductGroup("milk");

    ProductDocument doc = new ProductDocument();
    doc.participantInn = "1234567890";
    doc.productionDate = "2025-08-01";
    doc.usageType = "SOME_TYPE";
    // заполните остальные поля документа в соответствии со схемой

    String signatureBase64 = "..."; // открепленная подпись в base64

    try {
        String response = api.createIntroduceGoods(doc, signatureBase64);
        System.out.println("Created: " + response);
    } catch (Exception e) {
        e.printStackTrace();
    } finally {
        api.shutdown();
    }
    */
