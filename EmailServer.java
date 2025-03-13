package dev.mike;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class EmailServer {

    private static final String API_KEY = "RAPID API KEY HERE"; // Replace with your RapidAPI key
    private static final String GMAILNATOR_HOST = "gmailnator.p.rapidapi.com";
    private static final String EMAIL_CONTENTS = "EMAIL CONTENTS TO LOOK FOR";
    private static String GENERATED_EMAIL;

    private HttpServer server;
    private boolean running = false;

    public EmailServer() {}

    public void startServer(int PORT) throws IOException {
        if (running) return; // Prevent multiple starts
        server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/generate-email", new GenerateEmailHandler());
        server.createContext("/check-inbox", new CheckInboxHandler());
        server.setExecutor(null);
        server.start();
        running = true;
    }

    public void stopServer() {
        if (!running) return;
        server.stop(0);
        running = false;
    }

    public boolean isRunning() {
        return running;
    }

    static class GenerateEmailHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
                return;
            }
            try {
                GENERATED_EMAIL = generateCleanGmailEmail();
                JSONObject responseJson = new JSONObject().put("email", GENERATED_EMAIL);
                sendResponse(exchange, 200, responseJson.toString());
            } catch (Exception e) {
                sendResponse(exchange, 500, new JSONObject().put("error", e.getMessage()).toString());
            }
        }
    }

    static class CheckInboxHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
                return;
            }
            try {
                if (GENERATED_EMAIL == null || GENERATED_EMAIL.isEmpty()) {
                    sendResponse(exchange, 400, "{\"error\":\"No generated email available. Call /generate-email first.\"}");
                    return;
                }
                String verificationCode = fetchPartiVerificationCode(GENERATED_EMAIL);
                JSONObject responseJson = new JSONObject();
                responseJson.put("email", GENERATED_EMAIL);
                if (verificationCode != null) {
                    EmailServerGUI.log("Verification code received: " + verificationCode);
                    responseJson.put("verification_code", verificationCode);
                } else {
                    responseJson.put("error", "Verification email not found.");
                }
                sendResponse(exchange, 200, responseJson.toString());
            } catch (Exception e) {
                sendResponse(exchange, 500, new JSONObject().put("error", e.getMessage()).toString());
            }
        }
    }

    private static String generateCleanGmailEmail() throws Exception {
        String requestBody = "{\"options\":[2,3,5,6]}";
        HttpClient client = HttpClient.newHttpClient();
        char[] invalidChars = {'+', '='};
        int maxRetries = 5;
        int attempts = 0;
        while (attempts < maxRetries) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://" + GMAILNATOR_HOST + "/generate-email"))
                    .header("x-rapidapi-key", API_KEY)
                    .header("x-rapidapi-host", GMAILNATOR_HOST)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            JSONObject jsonResponse = new JSONObject(response.body());
            if (jsonResponse.has("email")) {
                String generatedEmail = jsonResponse.getString("email");
                boolean containsInvalidChar = false;
                for (char c : invalidChars) {
                    if (generatedEmail.indexOf(c) != -1) {
                        containsInvalidChar = true;
                        break;
                    }
                }
                if (!containsInvalidChar) {
                    EmailServerGUI.log("Generated clean Gmail: " + generatedEmail);
                    return generatedEmail;
                }
            }
            attempts++;
            Thread.sleep(2000);
        }
        throw new Exception("Failed to generate a clean Gmail after " + maxRetries + " attempts.");
    }

    // Check the inbox and extract the verification code
    public static String fetchPartiVerificationCode(String email) throws Exception {
        HttpClient client = HttpClient.newHttpClient();

        // Step 1: Fetch Inbox
        String requestBody = "{\"email\":\"" + email + "\",\"limit\":10}";
        HttpRequest inboxRequest = HttpRequest.newBuilder()
                .uri(URI.create("https://gmailnator.p.rapidapi.com/inbox"))
                .header("x-rapidapi-key", API_KEY)
                .header("x-rapidapi-host", GMAILNATOR_HOST)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> inboxResponse = client.send(inboxRequest, HttpResponse.BodyHandlers.ofString());
        String responseBody = inboxResponse.body();
        if (!responseBody.startsWith("[") || responseBody.equals("[]")) {
            System.out.println("No new emails found.");
            return null;
        }

        // Step 2: Parse Inbox and Find "Parti Email Verify"
        JSONArray messages = new JSONArray(responseBody);
        String messageId = null;

        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.getJSONObject(i);
            String sender = message.optString("from", "");
            String subject = message.optString("subject", "");

            if (subject.toLowerCase().contains(EMAIL_CONTENTS)) {
                messageId = message.getString("id");
                break;
            }
        }
        if (messageId == null) {
            System.out.println("Parti Email Verify message not found.");
            return null;
        }

        //System.out.println("Parti Email Verify message found with ID: " + messageId);
        //System.out.println("Fetching email content...");

        // Step 3: Fetch Email Content
        HttpRequest emailRequest = HttpRequest.newBuilder()
                .uri(URI.create("https://gmailnator.p.rapidapi.com/messageid?id=" + messageId))
                .header("x-rapidapi-key", API_KEY)
                .header("x-rapidapi-host", GMAILNATOR_HOST)
                .GET()
                .build();

        HttpResponse<String> emailResponse = client.send(emailRequest, HttpResponse.BodyHandlers.ofString());

        // Step 4: Extract Verification Code
        JSONObject emailContent = new JSONObject(emailResponse.body());
        String emailBody = emailContent.optString("content", "");
        return extractVerificationCode(emailBody);
    }

    private static String extractVerificationCode(String emailBody) {
        if (emailBody == null || emailBody.isEmpty()) {
            return null;
        }
        String regex = "<div>(\\d{6})</div>";
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(emailBody);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    // Helper function to send HTTP responses
    private static void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, response.length());
        OutputStream os = exchange.getResponseBody();
        os.write(response.getBytes());
        os.close();
    }
}
