package org.example;

import static spark.Spark.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;

import com.google.gson.Gson;

public class Main {
    private static Gson gson = new Gson();
    private static Connection connection;

    public static void main(String[] args) {
        // Настройка базы данных SQLite
        try {
            connection = DriverManager.getConnection("jdbc:sqlite:users.db");
            String createTableQuery = "CREATE TABLE IF NOT EXISTS users (username TEXT PRIMARY KEY, password TEXT)";
            try (PreparedStatement stmt = connection.prepareStatement(createTableQuery)) {
                stmt.execute();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        port(8080);

        // Настройка статических файлов
        staticFiles.location("/static");

        // Маршрут для главной страницы
        get("/", (req, res) -> {
            res.redirect("/index.html");
            return null;
        });

        // Маршрут для страницы логина и регистрации
        get("/auth", (req, res) -> {
            res.redirect("/auth.html");
            return null;
        });

        // Маршрут для страницы магазина
        get("/store", (req, res) -> {
            res.redirect("/store.html");
            return null;
        });

        // Маршрут для получения количества монет пользователя
        get("/user-coins", (req, res) -> {
            String username = req.session().attribute("username");
            if (username == null) {
                res.type("application/json");
                return gson.toJson(Map.of("success", false, "message", "Пользователь не авторизован"));
            }
            try {
                return executeWithRetry(() -> {
                    String query = "SELECT coins FROM users WHERE username = ?";
                    try (PreparedStatement stmt = connection.prepareStatement(query)) {
                        stmt.setString(1, username);
                        try (ResultSet rs = stmt.executeQuery()) {
                            if (rs.next()) {
                                int coins = rs.getInt("coins");
                                res.type("application/json");
                                return gson.toJson(Map.of("success", true, "coins", coins));
                            } else {
                                res.type("application/json");
                                return gson.toJson(Map.of("success", false, "message",
                                        "Пользователь не найден"));
                            }
                        }
                    }
                });
            } catch (Exception e) {
                e.printStackTrace();
                res.status(500);
                return gson.toJson(Map.of("success", false, "message", "Ошибка сервера"));
            }
        });

        // Маршрут для покупки товара
        post("/buy-item", (req, res) -> {
            String username = req.session().attribute("username");
            if (username == null) {
                res.type("application/json");
                return gson.toJson(Map.of("success", false, "message", "Пользователь не авторизован"));
            }

            String itemName = req.queryParams("item_name");
            int itemCost = Integer.parseInt(req.queryParams("item_cost"));

            try {
                return executeWithRetry(() -> {
                    connection.setAutoCommit(false); // Начинаем транзакцию

                    String query = "SELECT coins FROM users WHERE username = ?";
                    try (PreparedStatement stmt = connection.prepareStatement(query)) {
                        stmt.setString(1, username);
                        try (ResultSet rs = stmt.executeQuery()) {
                            if (rs.next()) {
                                int coins = rs.getInt("coins");
                                if (coins >= itemCost) {
                                    String updateCoinsQuery = "UPDATE users SET coins = coins - ? WHERE username = ?";
                                    try (PreparedStatement stmt2 = connection.prepareStatement(updateCoinsQuery)) {
                                        stmt2.setInt(1, itemCost);
                                        stmt2.setString(2, username);
                                        stmt2.execute();
                                    }

                                    String insertPurchaseQuery = "INSERT INTO purchases (username, item_name) " +
                                            "VALUES (?, ?)";
                                    try (PreparedStatement stmt3 = connection.prepareStatement(insertPurchaseQuery)) {
                                        stmt3.setString(1, username);
                                        stmt3.setString(2, itemName);
                                        stmt3.execute();
                                    }

                                    connection.commit(); // Завершаем транзакцию
                                    connection.setAutoCommit(true); // Восстанавливаем автокоммит

                                    res.type("application/json");
                                    return gson.toJson(Map.of("success", true, "message",
                                            "Покупка успешна"));
                                } else {
                                    connection.rollback(); // Откатываем транзакцию
                                    connection.setAutoCommit(true); // Восстанавливаем автокоммит

                                    res.type("application/json");
                                    return gson.toJson(Map.of("success", false, "message",
                                            "Недостаточно монет"));
                                }
                            } else {
                                connection.rollback(); // Откатываем транзакцию
                                connection.setAutoCommit(true); // Восстанавливаем автокоммит

                                res.type("application/json");
                                return gson.toJson(Map.of("success", false, "message", "Пользователь " +
                                        "не найден"));
                            }
                        }
                    } catch (Exception e) {
                        connection.rollback(); // Откатываем транзакцию при ошибке
                        connection.setAutoCommit(true); // Восстанавливаем автокоммит
                        throw e;
                    }
                });
            } catch (Exception e) {
                e.printStackTrace();
                res.status(500);
                return gson.toJson(Map.of("success", false, "message", "Ошибка сервера"));
            }
        });

        // Маршрут для проверки статуса входа
        get("/login-status", (req, res) -> {
            String username = req.session().attribute("username");
            if (username != null) {
                res.type("application/json");
                return gson.toJson(Map.of("success", true, "username", username));
            } else {
                res.type("application/json");
                return gson.toJson(Map.of("success", false));
            }
        });

        // Маршрут для выхода из аккаунта
        get("/logout", (req, res) -> {
            req.session().removeAttribute("username");
            res.type("application/json");
            return gson.toJson(Map.of("success", true));
        });

        // Маршрут для регистрации
        post("/register", (req, res) -> {
            String username = req.queryParams("username");
            String password = req.queryParams("password");
            if (username == null || password == null) {
                res.status(400);
                return gson.toJson(Map.of("success", false, "message", "Отсутствуют обязательные " +
                        "параметры"));
            }
            try {
                String checkUserQuery = "SELECT * FROM users WHERE username = ?";
                try (PreparedStatement stmt = connection.prepareStatement(checkUserQuery)) {
                    stmt.setString(1, username);
                    ResultSet rs = stmt.executeQuery();
                    if (rs.next()) {
                        res.status(400);
                        return gson.toJson(Map.of("success", false, "message", "Пользователь уже " +
                                "существует"));
                    }
                }

                String insertUserQuery = "INSERT INTO users (username, password) VALUES (?, ?)";
                try (PreparedStatement stmt = connection.prepareStatement(insertUserQuery)) {
                    stmt.setString(1, username);
                    stmt.setString(2, password);
                    stmt.execute();
                }
                req.session().attribute("username", username);
                return gson.toJson(Map.of("success", true, "message", "Регистрация успешна"));
            } catch (Exception e) {
                res.status(500);
                return gson.toJson(Map.of("success", false, "message", "Ошибка сервера"));
            }
        });

        // Маршрут для логина
        post("/login", (req, res) -> {
            String username = req.queryParams("username");
            String password = req.queryParams("password");
            if (username == null || password == null) {
                res.status(400);
                return gson.toJson(Map.of("success", false, "message", "Отсутствуют обязательные " +
                        "параметры"));
            }
            try {
                String loginUserQuery = "SELECT * FROM users WHERE username = ? AND password = ?";
                try (PreparedStatement stmt = connection.prepareStatement(loginUserQuery)) {
                    stmt.setString(1, username);
                    stmt.setString(2, password);
                    ResultSet rs = stmt.executeQuery();
                    if (!rs.next()) {
                        res.status(400);
                        return gson.toJson(Map.of("success", false, "message", "Неправильное имя " +
                                "пользователя или пароль"));
                    }
                }
                req.session().attribute("username", username);
                return gson.toJson(Map.of("success", true, "message", "Вход успешен"));
            } catch (Exception e) {
                res.status(500);
                return gson.toJson(Map.of("success", false, "message", "Ошибка сервера"));
            }
        });

        // Маршрут для страницы обучения
        get("/training", (req, res) -> {
            String username = req.session().attribute("username");
            if (username == null) {
                res.redirect("/auth.html");
                return null;
            }

            String taskQuery = "SELECT * FROM tasks";
            try (PreparedStatement stmt = connection.prepareStatement(taskQuery)) {
                ResultSet rs = stmt.executeQuery();
                Map<Integer, Map<String, String>> tasks = new HashMap<>();
                while (rs.next()) {
                    Map<String, String> taskDetails = new HashMap<>();
                    taskDetails.put("title", rs.getString("title"));
                    taskDetails.put("description", rs.getString("description"));
                    taskDetails.put("video_url", rs.getString("video_url"));
                    tasks.put(rs.getInt("id"), taskDetails);
                }
                res.type("application/json");
                return gson.toJson(Map.of("tasks", tasks));
            } catch (Exception e) {
                res.status(500);
                return gson.toJson(Map.of("success", false, "message", "Ошибка при получении задач"));
            }
        });

        // Маршрут для получения прогресса пользователя
        get("/user-progress", (req, res) -> {
            String username = req.session().attribute("username");
            if (username == null) {
                res.type("application/json");
                return gson.toJson(Map.of("success", false, "message", "Пользователь не авторизован"));
            }

            String userTasksQuery = "SELECT * FROM user_tasks WHERE username = ?";
            try (PreparedStatement stmt = connection.prepareStatement(userTasksQuery)) {
                stmt.setString(1, username);
                ResultSet rs = stmt.executeQuery();
                Map<Integer, Integer> userTasks = new HashMap<>();
                while (rs.next()) {
                    userTasks.put(rs.getInt("task_id"), rs.getInt("completed"));
                }
                res.type("application/json");
                return gson.toJson(Map.of("success", true, "userTasks", userTasks));
            } catch (Exception e) {
                res.status(500);
                return gson.toJson(Map.of("success", false, "message", "Ошибка при получении " +
                        "прогресса пользователя"));
            }
        });

        // Маршрут для получения ответов на задание
        get("/task-answers", (req, res) -> {
            int taskId = Integer.parseInt(req.queryParams("task_id"));

            String answersQuery = "SELECT id, answer_text FROM answers WHERE task_id = ?";
            try (PreparedStatement stmt = connection.prepareStatement(answersQuery)) {
                stmt.setInt(1, taskId);
                ResultSet rs = stmt.executeQuery();
                List<Map<String, String>> answers = new ArrayList<>();
                while (rs.next()) {
                    Map<String, String> answerDetails = new HashMap<>();
                    answerDetails.put("id", String.valueOf(rs.getInt("id")));
                    answerDetails.put("answer_text", rs.getString("answer_text"));
                    answers.add(answerDetails);
                }
                res.type("application/json");
                return gson.toJson(Map.of("answers", answers));
            } catch (Exception e) {
                res.status(500);
                return gson.toJson(Map.of("success", false, "message", "Ошибка при получении ответов"));
            }
        });

        // Маршрут для проверки ответа и обновления прогресса пользователя
        post("/check-answer", (req, res) -> {
            String username = req.session().attribute("username");
            if (username == null) {
                res.type("application/json");
                return gson.toJson(Map.of("success", false, "message", "Пользователь не авторизован"));
            }

            int taskId = Integer.parseInt(req.queryParams("task_id"));
            int answerId = Integer.parseInt(req.queryParams("answer_id"));

            try {
                String checkAnswerQuery = "SELECT is_correct FROM answers WHERE id = ? AND task_id = ?";
                try (PreparedStatement stmt = connection.prepareStatement(checkAnswerQuery)) {
                    stmt.setInt(1, answerId);
                    stmt.setInt(2, taskId);
                    ResultSet rs = stmt.executeQuery();
                    if (rs.next()) {
                        int isCorrect = rs.getInt("is_correct");
                        System.out.println("is_correct value: " + isCorrect); // Отладочная информация
                        if (isCorrect == 1) {
                            String updateProgressQuery = "INSERT INTO user_tasks (username, task_id, completed) VALUES "
                                    + "(?, ?, 1) ON CONFLICT(username, task_id) DO UPDATE SET completed = 1";
                            try (PreparedStatement stmt2 = connection.prepareStatement(updateProgressQuery)) {
                                stmt2.setString(1, username);
                                stmt2.setInt(2, taskId);
                                stmt2.execute();
                                System.out.println("Progress updated for user: " + username + " task_id: " + taskId);
                                // Отладочная информация
                            }

                            String updateCoinsQuery = "UPDATE users SET coins = coins + 30 WHERE username = ?";
                            try (PreparedStatement stmt3 = connection.prepareStatement(updateCoinsQuery)) {
                                stmt3.setString(1, username);
                                stmt3.execute();
                                System.out.println("Coins updated for user: " + username); // Отладочная информация
                            }

                            res.type("application/json");
                            return gson.toJson(Map.of("success", true, "message", "Ответ верный"));
                        } else {
                            res.type("application/json");
                            return gson.toJson(Map.of("success", false, "message", "Ответ неверный"));
                        }
                    } else {
                        res.type("application/json");
                        return gson.toJson(Map.of("success", false, "message", "Ответ не найден"));
                    }
                }
            } catch (Exception e) {
                e.printStackTrace(); // Отображаем стек вызовов ошибки в консоли сервера
                res.status(500);
                return gson.toJson(Map.of("success", false, "message", "Ошибка при проверке ответа: "
                        + e.getMessage()));
            }
        });


    }
    @FunctionalInterface
    interface SqlOperation {
        String execute() throws Exception;
    }

    private static String executeWithRetry(SqlOperation operation) throws Exception {
        int retryCount = 10; // Увеличиваем количество попыток
        int delay = 500; // Увеличиваем время задержки перед повторной попыткой
        while (retryCount > 0) {
            try {
                return operation.execute();
            } catch (Exception e) {
                if (e.getMessage().contains("SQLITE_BUSY")) {
                    retryCount--;
                    Thread.sleep(delay); // Увеличиваем время задержки перед повторной попыткой
                } else {
                    throw e;
                }
            }
        }
        throw new Exception("Превышено количество попыток выполнения операции");
    }
}
