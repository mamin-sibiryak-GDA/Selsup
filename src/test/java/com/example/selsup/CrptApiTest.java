package com.example.selsup; // Пакет, в котором лежит тест

// Импорты JUnit 5 для аннотаций тестов и assert'ов
import org.junit.jupiter.api.AfterEach; // Выполняется после каждого теста
import org.junit.jupiter.api.BeforeEach; // Выполняется перед каждым тестом
import org.junit.jupiter.api.Test;       // Обозначает метод как тест
import org.mockito.ArgumentCaptor;       // Позволяет перехватывать аргументы вызовов моков

// Импорты Java для работы с HTTP и временем
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

// Статические импорты для упрощения записи assert'ов и мокито-методов
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Тестовый класс для CrptApi.
 * Проверяет корректность работы ограничителя запросов и формирование тела запроса.
 */
public class CrptApiTest {

    // Поле с тестируемым экземпляром CrptApi
    private CrptApi api;
    // Моки для HttpClient и HttpResponse
    private HttpClient mockHttpClient;
    private HttpResponse<String> mockResponse;

    @BeforeEach
    public void setUp() throws Exception {
        // Создаём API с лимитом 2 запроса в секунду
        api = new CrptApi(TimeUnit.SECONDS, 2);
        api.setBaseUrl("https://test.server"); // Фиктивный адрес сервера
        api.setAuthToken("testToken");         // Фиктивный токен
        api.setProductGroup("milk");           // Фиктивная группа товаров

        // Создаём моки HTTP клиента и ответа
        mockHttpClient = mock(HttpClient.class);
        mockResponse = mock(HttpResponse.class);

        // Настраиваем мок ответа: код 200 и JSON {"status":"ok"}
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn("{\"status\":\"ok\"}");

        // Настраиваем мок клиента: при вызове send() возвращаем мок-ответ
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(invocation -> {
                    // Симулируем небольшую задержку, как будто был реальный запрос
                    Thread.sleep(50);
                    return mockResponse;
                });

        // Подменяем приватное поле httpClient в CrptApi на наш мок
        var field = CrptApi.class.getDeclaredField("httpClient"); // получаем объект поля
        field.setAccessible(true);                                // делаем доступным для изменения
        field.set(api, mockHttpClient);                           // устанавливаем мок вместо реального клиента
    }

    @AfterEach
    public void tearDown() {
        // Закрываем API после теста (останавливает планировщик refillPermits)
        api.shutdown();
    }

    @Test
    public void testSingleRequest() throws Exception {
        // Создаём тестовый документ
        CrptApi.ProductDocument doc = new CrptApi.ProductDocument();
        doc.participantInn = "1234567890";
        doc.productionDate = "2025-08-01";
        doc.usageType = "TEST";

        // Вызываем создание документа
        String response = api.createIntroduceGoods(doc, "signatureBase64");

        // Проверяем, что ответ совпадает с моковым
        assertEquals("{\"status\":\"ok\"}", response);

        // Проверяем, что HttpClient.send() вызывался ровно один раз
        verify(mockHttpClient, times(1))
                .send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    public void testRateLimitBlocksExtraRequests() throws Exception {
        // Документ для теста
        CrptApi.ProductDocument doc = new CrptApi.ProductDocument();
        doc.participantInn = "1234567890";
        doc.productionDate = "2025-08-01";
        doc.usageType = "TEST";

        long start = System.currentTimeMillis();

        // Делаем 3 вызова подряд, при лимите 2/сек
        // Третий должен ждать примерно 1 секунду, пока семафор освободится
        for (int i = 0; i < 3; i++) {
            api.createIntroduceGoods(doc, "sig");
        }

        long elapsedMs = System.currentTimeMillis() - start;

        // Проверяем, что общее время >= 1000 мс (т.е. была пауза)
        assertTrue(elapsedMs >= 1000, "3-й запрос должен был ждать восстановления лимита");

        // Проверяем, что send() вызван 3 раза
        verify(mockHttpClient, times(3))
                .send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    public void testRequestBodyContainsBase64Doc() throws Exception {
        // Документ для теста
        CrptApi.ProductDocument doc = new CrptApi.ProductDocument();
        doc.participantInn = "1234567890";
        doc.productionDate = "2025-08-01";
        doc.usageType = "TEST";

        // Получаем сформированное JSON-тело запроса напрямую из метода API
        String jsonBody = api.buildCreateRequestBody(doc, "sig");

        // Проверяем, что в JSON есть ключевые поля
        assertTrue(jsonBody.contains("LP_INTRODUCE_GOODS"), "Тело запроса не содержит тип документа");
        assertTrue(jsonBody.contains("product_document"), "Тело запроса не содержит product_document");
        assertTrue(jsonBody.contains("sig"), "Тело запроса не содержит подпись");
    }

}
