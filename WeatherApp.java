import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Scanner;

public class WeatherApp {
    private static final int PORT = 8080;

    public static void main(String[] args) throws IOException {
        System.out.println("Starting WeatherApp server...");

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);

        // Context for serving static frontend files
        server.createContext("/", new StaticFileHandler());

        // Context for API endpoint current weather
        server.createContext("/api/weather", new WeatherApiHandler());

        // Context for API endpoint forecast
        server.createContext("/api/forecast", new ForecastApiHandler());

        server.setExecutor(null); // creates a default executor
        server.start();

        System.out.println("Server started on http://localhost:" + PORT);

        // Interactive Console Prompt
        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.print("\nEnter city name to get weather (or 'exit' to quit): ");
            try {
                if (!scanner.hasNextLine())
                    break;
                String city = scanner.nextLine().trim();
                if (city.equalsIgnoreCase("exit")) {
                    break;
                }
                if (!city.isEmpty()) {
                    fetchAndPrintWeather(city);
                }
            } catch (Exception e) {
                break;
            }
        }

        System.out.println("Shutting down server...");
        server.stop(0);
        System.exit(0);
    }

    // Fetch data using Open-Meteo Geocoding + Weather API (Free, No Key)
    private static String getWeatherData(String city, boolean isForecast) {
        try {
            // Step 1: Geocoding
            String decodedCity = java.net.URLDecoder.decode(city, StandardCharsets.UTF_8.name());
            String encodedCity = URLEncoder.encode(decodedCity, StandardCharsets.UTF_8);
            String geoUrlStr = "https://geocoding-api.open-meteo.com/v1/search?name=" + encodedCity
                    + "&count=1&format=json";
            @SuppressWarnings("deprecation")
            URL geoUrl = new URL(geoUrlStr);
            HttpURLConnection geoConn = (HttpURLConnection) geoUrl.openConnection();
            geoConn.setRequestMethod("GET");
            geoConn.setRequestProperty("User-Agent", "Mozilla/5.0 WeatherApp/1.0");

            if (geoConn.getResponseCode() != 200) {
                return "{\"error\":\"Geocoding error. HTTP: " + geoConn.getResponseCode() + "\"}";
            }

            BufferedReader geoIn = new BufferedReader(new InputStreamReader(geoConn.getInputStream()));
            StringBuilder geoResp = new StringBuilder();
            String line;
            while ((line = geoIn.readLine()) != null)
                geoResp.append(line);
            geoIn.close();

            String geoJson = geoResp.toString();
            if (!geoJson.contains("\"latitude\":")) {
                return "{\"error\":\"City not found.\"}";
            }

            String resolvedCityName = extractJsonString(geoJson, null, 0, "\"name\":");
            String latStr = extractJsonValue(geoJson, null, 0, "\"latitude\":");
            String lonStr = extractJsonValue(geoJson, null, 0, "\"longitude\":");

            if (latStr == null || lonStr == null)
                return "{\"error\":\"Invalid geocoding response\"}";

            // Step 2: Weather Forecast
            String weatherUrlStr = "https://api.open-meteo.com/v1/forecast?latitude=" + latStr + "&longitude=" + lonStr
                    + "&current=temperature_2m,relative_humidity_2m,apparent_temperature,precipitation,weather_code,surface_pressure,wind_speed_10m"
                    + "&hourly=temperature_2m,weather_code"
                    + "&timezone=auto&forecast_days=3";

            @SuppressWarnings("deprecation")
            URL weatherUrl = new URL(weatherUrlStr);
            HttpURLConnection weatherConn = (HttpURLConnection) weatherUrl.openConnection();
            weatherConn.setRequestMethod("GET");
            weatherConn.setRequestProperty("User-Agent", "Mozilla/5.0 WeatherApp/1.0");

            if (weatherConn.getResponseCode() != 200) {
                return "{\"error\":\"Weather error. HTTP: " + weatherConn.getResponseCode() + "\"}";
            }

            BufferedReader wIn = new BufferedReader(new InputStreamReader(weatherConn.getInputStream()));
            StringBuilder wResp = new StringBuilder();
            while ((line = wIn.readLine()) != null)
                wResp.append(line);
            wIn.close();

            String weatherJson = wResp.toString();
            return transformOpenMeteoToOurFormat(weatherJson, resolvedCityName != null ? resolvedCityName : city,
                    isForecast);

        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    // Transform OpenMeteo JSON format to the format our frontend expects
    private static String transformOpenMeteoToOurFormat(String meteoJson, String city, boolean isForecast) {
        try {
            if (isForecast) {
                StringBuilder forecast = new StringBuilder("{\"list\":[");
                String hourlyObj = extractJsonObject(meteoJson, "\"hourly\":");
                if (hourlyObj == null)
                    return "{\"error\":\"No hourly data\"}";

                String timeArrStr = extractJsonArrayStr(hourlyObj, "\"time\":");
                String tempArrStr = extractJsonArrayStr(hourlyObj, "\"temperature_2m\":");
                String codeArrStr = extractJsonArrayStr(hourlyObj, "\"weather_code\":");

                if (timeArrStr == null || tempArrStr == null || codeArrStr == null)
                    return "{\"error\":\"Missing forecast fields\"}";

                String[] times = parseJsonArray(timeArrStr);
                String[] temps = parseJsonArray(tempArrStr);
                String[] codes = parseJsonArray(codeArrStr);

                // Collect up to 45 hours (15 intervals of 3 hrs)
                int maxItems = Math.min(times.length, 45);
                boolean first = true;
                for (int i = 0; i < maxItems; i += 3) {
                    if (!first)
                        forecast.append(",");
                    first = false;

                    String t = times[i].replace("\"", "").replace("T", " ");
                    t = t + ":00"; // format dt_txt properly

                    String temp = temps[i];
                    String code = codes[i];
                    String desc = mapMeteoCodeToDesc(code);
                    String main = mapMeteoCodeToMain(code);

                    forecast.append("{\"dt_txt\":\"").append(t).append("\",")
                            .append("\"main\":{\"temp\":").append(temp).append("},")
                            .append("\"weather\":[{\"main\":\"").append(main).append("\",\"description\":\"")
                            .append(desc).append("\"}]}");
                }
                forecast.append("]}");
                return forecast.toString();

            } else {
                // Parse current condition
                String currentObj = extractJsonObject(meteoJson, "\"current\":");
                if (currentObj == null)
                    return "{\"error\":\"No current data\"}";

                String tempC = extractJsonValue(currentObj, null, 0, "\"temperature_2m\":");
                String feelsLike = extractJsonValue(currentObj, null, 0, "\"apparent_temperature\":");
                String humidity = extractJsonValue(currentObj, null, 0, "\"relative_humidity_2m\":");
                String pressure = extractJsonValue(currentObj, null, 0, "\"surface_pressure\":");
                String windSpeed = extractJsonValue(currentObj, null, 0, "\"wind_speed_10m\":");
                String codeStr = extractJsonValue(currentObj, null, 0, "\"weather_code\":");

                double windMs = 0;
                if (windSpeed != null) {
                    windMs = Double.parseDouble(windSpeed.replace("\"", "")) * (1000.0 / 3600.0);
                }

                String mainDesc = mapMeteoCodeToMain(codeStr);
                String fullDesc = mapMeteoCodeToDesc(codeStr);

                if (city.startsWith("\"") && city.endsWith("\"")) {
                    city = city.substring(1, city.length() - 1);
                }

                return "{"
                        + "\"weather\":[{\"main\":\"" + mainDesc + "\",\"description\":\"" + fullDesc + "\"}],"
                        + "\"main\":{\"temp\":" + (tempC != null ? tempC : "0") + ",\"feels_like\":"
                        + (feelsLike != null ? feelsLike : "0") + ",\"pressure\":" + (pressure != null ? pressure : "0")
                        + ",\"humidity\":" + (humidity != null ? humidity : "0") + "},"
                        + "\"wind\":{\"speed\":" + String.format("%.1f", windMs) + "},"
                        + "\"name\":\"" + city + "\","
                        + "\"cod\":200"
                        + "}";
            }
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    // WMO Weather interpretation codes
    private static String mapMeteoCodeToMain(String codeStr) {
        if (codeStr == null)
            return "Clouds";
        int code = Integer.parseInt(codeStr.replace("\"", ""));
        if (code == 0)
            return "Clear";
        if (code >= 1 && code <= 3)
            return "Clouds";
        if (code >= 51 && code <= 67)
            return "Rain";
        if (code >= 71 && code <= 77)
            return "Snow";
        if (code >= 80 && code <= 82)
            return "Rain";
        if (code >= 85 && code <= 86)
            return "Snow";
        if (code >= 95 && code <= 99)
            return "Thunderstorm";
        return "Clouds";
    }

    private static String mapMeteoCodeToDesc(String codeStr) {
        if (codeStr == null)
            return "unknown";
        int code = Integer.parseInt(codeStr.replace("\"", ""));
        switch (code) {
            case 0:
                return "clear sky";
            case 1:
            case 2:
            case 3:
                return "partly cloudy";
            case 45:
            case 48:
                return "fog";
            case 51:
            case 53:
            case 55:
                return "drizzle";
            case 61:
            case 63:
            case 65:
                return "rain";
            case 71:
            case 73:
            case 75:
                return "snowfall";
            case 95:
            case 96:
            case 99:
                return "thunderstorm";
            default:
                return "cloudy";
        }
    }

    // Manual JSON Utilities
    private static String extractJsonObject(String json, String key) {
        int idx = json.indexOf(key);
        if (idx == -1)
            return null;
        int start = json.indexOf("{", idx);
        int end = findMatchingBrace(json, start, '{', '}');
        if (start == -1 || end == -1)
            return null;
        return json.substring(start, end + 1);
    }

    private static String extractJsonArrayStr(String json, String key) {
        int idx = json.indexOf(key);
        if (idx == -1)
            return null;
        int start = json.indexOf("[", idx);
        int end = findMatchingBrace(json, start, '[', ']');
        if (start == -1 || end == -1)
            return null;
        return json.substring(start, end + 1);
    }

    private static String[] parseJsonArray(String jsonArray) {
        if (jsonArray == null || jsonArray.length() < 2)
            return new String[0];
        String content = jsonArray.substring(1, jsonArray.length() - 1);
        return content.split(",");
    }

    private static int findMatchingBrace(String json, int start, char open, char close) {
        if (start == -1)
            return -1;
        int depth = 0;
        for (int i = start; i < json.length(); i++) {
            if (json.charAt(i) == open)
                depth++;
            if (json.charAt(i) == close)
                depth--;
            if (depth == 0)
                return i;
        }
        return -1;
    }

    private static String extractJsonValue(String stringBlock, String arrayKey, int index, String key) {
        if (stringBlock == null)
            return null;
        int startBounds = 0;
        if (arrayKey != null) {
            startBounds = stringBlock.indexOf(arrayKey);
            if (startBounds == -1)
                return null;
        }
        int keyIdx = stringBlock.indexOf(key, startBounds);
        if (keyIdx == -1)
            return null;

        int valStart = keyIdx + key.length();
        while (valStart < stringBlock.length()
                && (stringBlock.charAt(valStart) == ' ' || stringBlock.charAt(valStart) == ':')) {
            valStart++;
        }

        int valEnd = stringBlock.length();
        for (int i = valStart; i < stringBlock.length(); i++) {
            char c = stringBlock.charAt(i);
            if (c == ',' || c == '}' || c == ']') {
                valEnd = i;
                break;
            }
        }
        return stringBlock.substring(valStart, valEnd).trim();
    }

    private static String extractJsonString(String stringBlock, String arrayKey, int index, String key) {
        String val = extractJsonValue(stringBlock, arrayKey, index, key);
        if (val != null && !val.startsWith("\"")) {
            return "\"" + val + "\"";
        }
        return val;
    }

    // Parses JSON string manually and prints to console to meet requirement
    private static void fetchAndPrintWeather(String city) {
        System.out.println("Fetching weather for " + city + "...");
        String jsonStr = getWeatherData(city, false);

        if (jsonStr.contains("\"error\"")) {
            System.out.println("Failed to get weather data for " + city + ". " + jsonStr);
            return;
        }

        // Extremely simple parsing since we avoid external JSON libraries
        String temp = extractJsonValue(jsonStr, "\"temp\":");
        String feelsLike = extractJsonValue(jsonStr, "\"feels_like\":");
        String humidity = extractJsonValue(jsonStr, "\"humidity\":");
        String desc = extractJsonString(jsonStr, "\"description\":\"");
        String cityName = extractJsonString(jsonStr, "\"name\":\"");

        System.out.println("\n--------------------------------");
        System.out.println("Weather Summary for " + (cityName != null ? cityName : city));
        System.out.println("--------------------------------");
        System.out.println("Condition    : " + (desc != null ? desc : "Unknown"));
        System.out.println("Temperature  : " + (temp != null ? temp + " °C" : "N/A"));
        System.out.println("Feels Like   : " + (feelsLike != null ? feelsLike + " °C" : "N/A"));
        System.out.println("Humidity     : " + (humidity != null ? humidity + "%" : "N/A"));
        System.out.println("--------------------------------\n");
    }

    // Helper to find numeric value
    private static String extractJsonValue(String json, String key) {
        int index = json.indexOf(key);
        if (index == -1)
            return null;
        int startIndex = index + key.length();
        int endIndex = json.indexOf(",", startIndex);
        if (endIndex == -1)
            endIndex = json.indexOf("}", startIndex);
        if (endIndex == -1)
            return null;
        return json.substring(startIndex, endIndex).trim();
    }

    // Helper to find string value
    private static String extractJsonString(String json, String key) {
        int index = json.indexOf(key);
        if (index == -1)
            return null;
        int startIndex = index + key.length();
        int endIndex = json.indexOf("\"", startIndex);
        if (endIndex == -1)
            return null;
        return json.substring(startIndex, endIndex);
    }

    static class StaticFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/")) {
                path = "/index.html";
            }

            File file = new File("frontend" + path);
            if (!file.exists() || file.isDirectory()) {
                String response = "404 (Not Found)\n";
                exchange.sendResponseHeaders(404, response.length());
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
                return;
            }

            // Determine content type
            String contentType = "text/plain";
            if (path.endsWith(".html"))
                contentType = "text/html";
            else if (path.endsWith(".css"))
                contentType = "text/css";
            else if (path.endsWith(".js"))
                contentType = "application/javascript";

            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, file.length());

            try (OutputStream os = exchange.getResponseBody()) {
                Files.copy(file.toPath(), os);
            }
        }
    }

    static class WeatherApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String query = exchange.getRequestURI().getQuery();
            String city = "London"; // default

            if (query != null && query.startsWith("city=")) {
                city = query.substring(5);
            }

            String jsonResponse = getWeatherData(city, false);

            // Setup CORS and Headers
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");

            // Send response
            byte[] responseBytes = jsonResponse.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        }
    }

    static class ForecastApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String query = exchange.getRequestURI().getQuery();
            String city = "London"; // default

            if (query != null && query.startsWith("city=")) {
                city = query.substring(5);
            }

            String jsonResponse = getWeatherData(city, true);

            // Setup CORS and Headers
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");

            // Send response
            byte[] responseBytes = jsonResponse.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        }
    }
}
