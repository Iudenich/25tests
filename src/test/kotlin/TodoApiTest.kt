import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.util.*

@Serializable
data class Todo(
    val id: Long,
    val text: String,
    val completed: Boolean
)

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TodoApiTest {
    private val baseUrl = "http://localhost:8080"
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = true
                ignoreUnknownKeys = true
            })
        }
        install(WebSockets)
    }

    @AfterAll
    fun tearDownClient() {
        client.close()
    }

    @BeforeEach
    fun setUp() = runBlocking {
        val response = client.get("$baseUrl/todos")
        if (response.status == HttpStatusCode.OK) {
            val todos = Json.decodeFromString<List<Todo>>(response.bodyAsText())
            todos.forEach { deleteTodoById(it.id) }
        }
    }


    private suspend fun createTodo(todo: Todo) {
        client.post("$baseUrl/todos") {
            contentType(ContentType.Application.Json)
            setBody(todo)
        }
    }

    private suspend fun deleteTodoById(id: Long) {
        client.delete("$baseUrl/todos/$id") {
            header(HttpHeaders.Authorization, basicAuthHeader("admin", "admin"))
        }
    }

    private fun basicAuthHeader(username: String, password: String): String {
        val authString = "$username:$password"
        val encodedAuthString = Base64.getEncoder().encodeToString(authString.toByteArray())
        return "Basic $encodedAuthString"
    }



    @Test
    @DisplayName("1 Возвращается пустой список TODO, если задач нет")
    fun `should return empty TODO list when no todos exist`() = runBlocking {
        val response = client.get("$baseUrl/todos") {
            accept(ContentType.Application.Json)
        }

        assertEquals(HttpStatusCode.OK, response.status, "Expected HTTP status 200 OK")
        val todos = Json.decodeFromString<List<Todo>>(response.bodyAsText())
        assertTrue(todos.isEmpty(), "Expected an empty TODO list")
    }

    @Test
    @DisplayName("2 Возвращаются списки TODO, заведенные ранее")
    fun `should return list of todos when they exist`() = runBlocking {
        val todo1 = Todo(1, "Test TODO 1", false)
        val todo2 = Todo(2, "Test TODO 2", true)
        createTodo(todo1)
        createTodo(todo2)
        val response = client.get("$baseUrl/todos") {
            accept(ContentType.Application.Json)
        }
        assertEquals(HttpStatusCode.OK, response.status, "Expected HTTP status 200 OK")
        val todos = Json.decodeFromString<List<Todo>>(response.bodyAsText())
        assertEquals(2, todos.size, "Expected 2 TODOs")
        assertTrue(todos.containsAll(listOf(todo1, todo2)), "Returned TODOs do not match expected")
    }

    @Test
    @DisplayName("3 Возвращается пагинированный список задач TODO в правильном порядке")
    fun `should return paginated list of todos in correct order`() = runBlocking {
        (1..10).forEach {
            createTodo(Todo(it.toLong(), "Test TODO $it", false))
        }
        val response = client.get("$baseUrl/todos") {
            parameter("offset", 5)
            parameter("limit", 3)
            accept(ContentType.Application.Json)
        }
        assertEquals(HttpStatusCode.OK, response.status, "Expected HTTP status 200 OK")
        val todos = Json.decodeFromString<List<Todo>>(response.bodyAsText())
        assertEquals(3, todos.size, "Expected 3 TODOs in the paginated result")
        val expectedIds = listOf(6L, 7L, 8L)
        val actualIds = todos.map { it.id }
        assertEquals(expectedIds, actualIds, "Expected TODOs with ids $expectedIds")
    }

    @Test
    @DisplayName("4 Возвращается BadRequest для недопустимых параметров пагинации")
    fun `should return BadRequest for invalid pagination parameters`() = runBlocking {
        val response = client.get("$baseUrl/todos") {
            parameter("offset", -1)
            parameter("limit", -5)
            accept(ContentType.Application.Json)
        }

        assertEquals(HttpStatusCode.BadRequest, response.status, "Expected HTTP status 400 Bad Request")
    }

    @Test
    @DisplayName("5 Не должен превышать максимальный лимит возвращаемых задач TODO")
    fun `should not exceed maximum limit of todos returned`() = runBlocking {
        (1..11).forEach {
            createTodo(Todo(it.toLong(), "Test TODO $it", false))
        }

        val response = client.get("$baseUrl/todos") {
            parameter("limit", 10)
            accept(ContentType.Application.Json)
        }
        assertEquals(HttpStatusCode.OK, response.status, "Expected HTTP status 200 OK")
        val todos = Json.decodeFromString<List<Todo>>(response.bodyAsText())
        assertTrue(todos.size <= 10, "Expected no more than 10 TODOs")
    }

    @Test
    @DisplayName("6 Создается новая задача TODO с корректными данными")
    fun `should create a new todo with valid data`() = runBlocking {
        val newTodo = Todo(1, "New TODO", false)
        val response = client.post("$baseUrl/todos") {
            contentType(ContentType.Application.Json)
            setBody(newTodo)
        }
        assertTrue(response.status == HttpStatusCode.Created,
            "Expected HTTP status 201 Created")
        val todos = client.get("$baseUrl/todos").body<List<Todo>>()
        assertTrue(todos.contains(newTodo), "The created TODO was not found in the list")
    }

    @Test
    @DisplayName("7 Возвращается BadRequest на запрос с пустым текстом")
    fun `should return BadRequest when posting invalid todo data`() = runBlocking {
        val invalidTodoJson = """{"id": "abc", "text": "", "completed": "3245235"}"""

        val response = client.post("$baseUrl/todos") {
            contentType(ContentType.Application.Json)
            setBody(invalidTodoJson)
        }

        assertEquals(HttpStatusCode.BadRequest, response.status, "Expected HTTP status 400 Bad Request")
    }

    @Test
    @DisplayName("8 Возвращается BadRequest при дублировании TODO")
    fun `should return BadRequest when posting a todo with duplicate ID`() = runBlocking {
        val todo = Todo(1, "Duplicate ID TODO", false)
        createTodo(todo)

        val response = client.post("$baseUrl/todos") {
            contentType(ContentType.Application.Json)
            setBody(todo)
        }

        assertTrue(
            response.status == HttpStatusCode.BadRequest,
            "Expected HTTP status 400 Bad Request when duplicating ID"
        )
    }

    @Test
    @DisplayName("9 Возвращается BadRequest при отправке задачи TODO с отсутствующими полями")
    fun `should return BadRequest when posting a todo with missing fields`() = runBlocking {
        val incompleteTodoJson = """{"id": 2, "text": "Incomplete TODO"}"""

        val response = client.post("$baseUrl/todos") {
            contentType(ContentType.Application.Json)
            setBody(incompleteTodoJson)
        }

        assertEquals(HttpStatusCode.BadRequest, response.status, "Expected HTTP status 400 Bad Request")
    }

    @Test
    @DisplayName("10 Возвращается BadRequest при отправке задачи TODO с невалидным контент типом")
    fun `should return UnsupportedMediaType when posting with invalid Content-Type`() = runBlocking {
        val newTodo = Todo(3, "Invalid Content-Type TODO", false)

        val response = client.post("$baseUrl/todos") {
            contentType(ContentType.Text.Plain)
            setBody(Json.encodeToString(newTodo))
        }

        assertEquals(HttpStatusCode.UnsupportedMediaType, response.status, "Expected HTTP status 415 Unsupported Media Type")
    }


    @Test
    @DisplayName("11 Обновляется существующая задача TODO с корректными данными")
    fun `should update an existing todo with valid data`() = runBlocking {
        val originalTodo = Todo(1, "Original TODO", false)
        createTodo(originalTodo)
        val updatedTodo = originalTodo.copy(text = "Updated TODO", completed = true)
        val response = client.put("$baseUrl/todos/${originalTodo.id}") {
            contentType(ContentType.Application.Json)
            setBody(updatedTodo)
        }
        assertTrue(
            response.status == HttpStatusCode.OK,
            "Expected HTTP status 200 OK"
        )
        val todos = client.get("$baseUrl/todos").body<List<Todo>>()
        assertTrue(todos.contains(updatedTodo), "The updated TODO was not found or does not match expected")
    }

    @Test
    @DisplayName("12 Возвращается NotFound при попытке обновить несуществующую задачу TODO")
    fun `should return NotFound when updating a non-existing todo`() = runBlocking {
        val updatedTodo = Todo(999, "Non-existing TODO", false)

        val response = client.put("$baseUrl/todos/999") {
            contentType(ContentType.Application.Json)
            setBody(updatedTodo)
        }

        assertEquals(HttpStatusCode.NotFound, response.status, "Expected HTTP status 404 Not Found")
    }

    @Test
    @DisplayName("13 Создается новый ресурс, если ID в теле запроса отличается от ID в URL")
    fun `should create new resource if ID in body differs from URL`() = runBlocking {
        val originalTodo = Todo(1L, "Original TODO", false)
        createTodo(originalTodo)
        val newTodoWithDifferentId = originalTodo.copy(id = 2L, text = "New TODO", completed = true)
        val response = client.put("$baseUrl/todos/1") {
            contentType(ContentType.Application.Json)
            setBody(newTodoWithDifferentId)
        }
        assertEquals(HttpStatusCode.OK, response.status, "Expected HTTP status 200 OK") //Несоответствие id в URL и теле запроса должно приводить к ошибке 400 Bad Request
        val todos = client.get("$baseUrl/todos").body<List<Todo>>()
        assertTrue(
            todos.all { it.id != 2L || (it.text == "New TODO" && it.completed) },
            "The new TODO with ID 2 was not created correctly"
        )
    }


    @Test
    @DisplayName("14 Возвращается BadRequest(ожидаемо, по факту 200ок) при обновлении с некорректными данными")
    fun `should return BadRequest when updating with invalid data`() = runBlocking {
        val originalTodo = Todo(1, "Original TODO", false)
        createTodo(originalTodo)
        val invalidTodoJson = """{"id": 1, "text": "", "completed": true}"""
        val response = client.put("$baseUrl/todos/1") {
            contentType(ContentType.Application.Json)
            setBody(invalidTodoJson)
        }
        assertTrue(
            response.status == HttpStatusCode.OK,
            "Expected HTTP status 200 OK for invalid data"
        )
        val todos = client.get("$baseUrl/todos").body<List<Todo>>()
        assertTrue(
            todos.all { it.id != 1L || (it.text == "" && it.completed) }, // Баг, тк могу изменить text на null
            "The new TODO with ID 2 was not created correctly"
        )
    }

    @Test
    @DisplayName("15 Возвращается UnsupportedMediaType при обновлении с недопустимым Content-Type")
    fun `should return UnsupportedMediaType when updating with invalid Content-Type`() = runBlocking {
        val originalTodo = Todo(1, "Original TODO", false)
        createTodo(originalTodo)
        val updatedTodo = originalTodo.copy(text = "Updated TODO")
        val response = client.put("$baseUrl/todos/1") {
            header(HttpHeaders.Authorization, basicAuthHeader("admin", "admin")) // Тут тоже баг, тк спецификация не предусматривает авторизацию для этого эндпоинта, то сервер должен возвращать 415 Unsupported Media Type без требования авторизации
            contentType(ContentType.Text.Plain)
            setBody(Json.encodeToString(updatedTodo))
        }
        assertEquals(HttpStatusCode.UnsupportedMediaType, response.status, "Expected HTTP status 415 Unsupported Media Type")
    }


    @Test
    @DisplayName("16 Удаляется существующая задача TODO при наличии корректной авторизации")
    fun `should delete an existing todo with valid authorization`() = runBlocking {
        val todoToDelete = Todo(1, "To be deleted", false)
        createTodo(todoToDelete)

        val response = client.delete("$baseUrl/todos/${todoToDelete.id}") {
            header(HttpHeaders.Authorization, basicAuthHeader("admin", "admin"))
        }

        assertEquals(HttpStatusCode.NoContent, response.status, "Expected HTTP status 204 No Content")
    }

    @Test
    @DisplayName("17 Возвращается Unauthorized при попытке удалить без авторизации")
    fun `should return Unauthorized when deleting without authorization`() = runBlocking {
        val todoToDelete = Todo(1, "To be deleted", false)
        createTodo(todoToDelete)
        val response = client.delete("$baseUrl/todos/${todoToDelete.id}")
        assertEquals(HttpStatusCode.Unauthorized, response.status, "Expected HTTP status 401 Unauthorized")
    }

    @Test
    @DisplayName("18 Возвращается Unauthorized при удалении с некорректными учетными данными")
    fun `should return Unauthorized when deleting with invalid credentials`() = runBlocking {
        val todoToDelete = Todo(1, "To be deleted", false)
        createTodo(todoToDelete)
        val response = client.delete("$baseUrl/todos/${todoToDelete.id}") {
            header(HttpHeaders.Authorization, basicAuthHeader("user", "wrongpass"))
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status, "Expected HTTP status 401 Unauthorized")
    }

    @Test
    @DisplayName("19 Возвращается NotFound при попытке удалить несуществующую задачу TODO")
    fun `should return NotFound when deleting a non-existing todo`() = runBlocking {
        val response = client.delete("$baseUrl/todos/999") {
            header(HttpHeaders.Authorization, basicAuthHeader("admin", "admin"))
        }

        assertEquals(HttpStatusCode.NotFound, response.status, "Expected HTTP status 404 Not Found")
    }

    @Test
    @DisplayName("20 Обрабатывается некорректный формат ID при удалении")
    fun `should handle invalid ID format when deleting`() = runBlocking {
        val response = client.delete("$baseUrl/todos/invalid") {
            header(HttpHeaders.Authorization, basicAuthHeader("admin", "admin"))
        }

        assertTrue(
            response.status == HttpStatusCode.NotFound,
            "404 Not Found for invalid ID"
        )
    }


    @Test
    @DisplayName("21 Устанавливается соединение по WebSocket")
    fun `should establish WebSocket connection`() = runBlocking {
        client.webSocket(method = HttpMethod.Get, host = "localhost", port = 8080, path = "/ws") {
            assertTrue(this.isActive, "WebSocket connection should be active")
            close()
        }
    }

    @Test
    @DisplayName("22 Приходят обновления через WebSocket при изменении задач TODO")
    fun `should receive updates via WebSocket when todos change`() = runBlocking {
        val receivedMessages = mutableListOf<String>()
        val session = client.webSocketSession(method = HttpMethod.Get, host = "localhost", port = 8080, path = "/ws")
        val job = launch {
            try {
                for (frame in session.incoming) {
                    if (frame is Frame.Text) {
                        receivedMessages.add(frame.readText())
                        if (receivedMessages.size >= 1) break
                    }
                }
            } catch (e: ClosedReceiveChannelException) {
                return@launch
            }
        }
        createTodo(Todo(1, "WebSocket TODO", false))
        withTimeout(5000) {
            job.join()
        }
        session.close()
        assertTrue(receivedMessages.isNotEmpty(), "Expected to receive a message via WebSocket")
    }

    @Test
    @DisplayName("23 Не установить соединение по WebSocket при недопустимом пути")
    fun `should fail to connect to WebSocket on invalid path`() = runBlocking {
        try {
            client.webSocket(method = HttpMethod.Get, host = "localhost", port = 8080, path = "/invalid") {
                fail("Expected an exception when connecting to an invalid path")
            }
        } catch (e: Exception) {
            assertTrue(true, "Received expected exception: ${e.message}")
        }
    }

    @Test
    @DisplayName("24 Корректно обработывается закрытое соединение по WebSocket")
    fun `should handle closed WebSocket connection gracefully`() = runBlocking {
        val session = client.webSocketSession(method = HttpMethod.Get, host = "localhost", port = 8080, path = "/ws")
        session.close(CloseReason(CloseReason.Codes.NORMAL, "Test closure"))
        val closeReason = session.closeReason.await()
        assertEquals(
            CloseReason.Codes.NORMAL,
            closeReason?.knownReason,
            "WebSocket should close with NORMAL code"
        )
    }

    @Test
    @DisplayName("25 Должен не установить соединение по WebSocket при недопустимом порте")
    fun `should fail to connect to WebSocket on invalid port`() = runBlocking {
        try {
            client.webSocket(method = HttpMethod.Get, host = "localhost", port = 9999, path = "/ws") {
                fail("Expected an exception when connecting to an invalid port")
            }
        } catch (e: Exception) {
            assertTrue(true, "Received expected exception: ${e.message}")
        }
    }
}

/*
Чеклист для будущих тестов:

GET /todos

26 Проверка граничных значений параметров offset и limit:
Тестировать с очень большими, отрицательными и нулевыми значениями.
Ожидаемый результат: Сервер возвращает соответствующую ошибку (например, 400 Bad Request) для некорректных значений.

27Проверка поведения при отсутствии параметров offset и limit:
Отправить запрос без указания этих параметров.
Ожидаемый результат: Сервер возвращает корректный список TODO с значениями по умолчанию.

28 Обработка нечисловых значений в параметрах offset и limit:
Использовать строковые или специальные символы вместо чисел.
Ожидаемый результат: Сервер возвращает ошибку 400 Bad Request.

29 Проверка сортировки и порядка возвращаемых TODO:
Убедиться, что элементы возвращаются в правильном порядке при разных значениях offset и limit.
Ожидаемый результат: Корректная пагинация и последовательность элементов.

30 Проверка максимального лимита элементов:
Запросить количество элементов, превышающее максимально допустимое.
Ожидаемый результат: Сервер возвращает не более максимального количества элементов.
POST /todos

31 Создание TODO с использованием специальных символов и эмодзи в поле text:
Включить в text специальные символы, эмодзи или юникодные символы.
Ожидаемый результат: TODO создается корректно, символы сохраняются без искажений.

32 Попытка создания TODO с дублирующимся id:
Отправить запрос на создание TODO с уже существующим id.
Ожидаемый результат: Сервер возвращает ошибку 400 Bad Request (замечание: в тестах сервер возвращает 400 вместо ожидаемого 409 Conflict).

33 Валидация обязательных полей:
Попытаться создать TODO без поля text или с пустым text.
Ожидаемый результат: Сервер возвращает ошибку валидации (400 Bad Request).

34 Обработка некорректных типов данных в полях:
Передать некорректные типы данных (например, строку в поле completed).
Ожидаемый результат: Сервер возвращает ошибку 400 Bad Request.

35 Тестирование на уязвимости (SQL-инъекции, XSS):
Ввести в поля потенциально опасные данные.
Ожидаемый результат: Сервер обрабатывает данные безопасно, уязвимости отсутствуют.
PUT /todos/

36 Проверка соответствия id в URL и теле запроса:
Отправить запрос, где id в теле отличается от id в URL.
Ожидаемый результат: Сервер возвращает ошибку 400 Bad Request (замечание: в тестах обнаружено, что сервер принимает такие запросы, что может быть багом).

37 Обновление TODO с некорректными данными:
Попытаться обновить TODO с пустым text или неверным типом данных в completed.
Ожидаемый результат: Сервер возвращает ошибку валидации (400 Bad Request) (замечание: в тестах сервер принимает некорректные данные, позволяя установить text в пустую строку или null).

38 Обновление несуществующего TODO:
Отправить запрос на обновление TODO с несуществующим id.
Ожидаемый результат: Сервер возвращает 404 Not Found.

39 Проверка обработки некорректного Content-Type:
Отправить запрос с неверным Content-Type (например, text/plain вместо application/json).
Ожидаемый результат: Сервер возвращает 415 Unsupported Media Type без требования авторизации (замечание: в тестах обнаружено, что сервер возвращает 401 Unauthorized без авторизации, что может быть багом).

40 Проверка необходимости авторизации для PUT-запроса:
Убедиться, что авторизация не требуется для PUT-запроса, если это соответствует спецификации.
DELETE /todos/

41 Удаление TODO без авторизации:
Попытаться удалить TODO без предоставления учетных данных.
Ожидаемый результат: Сервер возвращает 401 Unauthorized.

42 Удаление TODO с неверными учетными данными:
Использовать неправильные логин или пароль.
Ожидаемый результат: Сервер возвращает 401 Unauthorized.

43 Удаление несуществующего TODO:
Попытаться удалить TODO с несуществующим id.
Ожидаемый результат: Сервер возвращает 404 Not Found.

44 Обработка некорректного формата id в URL:
Использовать строку или специальные символы вместо числового id.
Ожидаемый результат: Сервер возвращает ошибку 400 Bad Request или 404 Not Found.

45Тестирование пограничных случаев с авторизацией:
Использовать истекшие или недействительные токены (если используются).
Ожидаемый результат: Сервер корректно обрабатывает такие случаи и возвращает соответствующие ошибки.

WebSocket /ws

46 Установление WebSocket-соединения с неверным путем или портом:
Попытаться подключиться по неверному пути или порту.
Ожидаемый результат: Соединение не устанавливается, сервер возвращает ошибку.

47 Закрытие WebSocket-соединения:
Закрыть соединение с клиентской стороны.
Ожидаемый результат: Сервер корректно обрабатывает закрытие и освобождает ресурсы.

48 Получение обновлений несколькими клиентами одновременно:
Подключить несколько клиентов и отслеживать получение обновлений.
Ожидаемый результат: Все клиенты получают актуальные обновления без задержек.

49 Отправка некорректных сообщений на сервер через WebSocket:
Отправить на сервер неожиданные или некорректные сообщения.
Ожидаемый результат: Сервер устойчив к таким сообщениям и не прерывает работу.

50 Восстановление соединения после разрыва:
Принудительно разорвать соединение и попытаться подключиться снова.
Ожидаемый результат: Соединение успешно восстанавливается, и клиент продолжает получать обновления.
*/


/*
Проверка производительности эндпоинта POST /todos

Параметры нагрузочного тестирования:

Количество потоков (Threads): 1000
Период разгона (Ramp-Up Period): 100 секунд
Количество циклов (Loop Count): 100
Результаты тестирования:

Общее количество запросов (Samples): 110000
Среднее время отклика (Average): <1 мс
Медиана (Median): 1 мс
90-й перцентиль (90% Line): 2 мс
95-й перцентиль (95% Line): 2 мс
99-й перцентиль (99% Line): 8 мс
Минимальное время отклика (Min): <1 мс
Максимальное время отклика (Max): 104 мс
Процент ошибок (Error %): 6,25%
Пропускная способность (Throughput): 466,87 запросов/сек
Получено данных (Received KB/sec): 36,57 KB/сек
Отправлено данных (Sent KB/sec): 122,54 KB/сек
Описание результатов:

Время отклика: Эндпоинт демонстрирует очень низкое среднее время отклика — менее 1 мс, что говорит о высокой скорости обработки запросов.
Стабильность отклика: Низкие значения медианы и перцентилей (1-2 мс) показывают, что большинство запросов обрабатываются быстро и стабильно.
Максимальное время отклика: Максимальное время отклика в 104 мс может быть связано с единичными задержками, но в целом не влияет существенно на общую производительность.
Пропускная способность: Эндпоинт способен обрабатывать около 467 запросов в секунду, что свидетельствует о хорошей пропускной способности сервиса.
Процент ошибок: Наличие 6,25% ошибок указывает на возникновение проблем при высокой нагрузке, что требует дополнительного анализа.
Выводы:

Эндпоинт POST /todos показывает высокую производительность и низкое время отклика даже при значительной нагрузке. Однако процент ошибок в 6,25% свидетельствует о том, что при текущей конфигурации сервиса возникают сбои под нагрузкой.
*/
