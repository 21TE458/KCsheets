package com.kc;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.SheetsScopes;
import com.google.api.services.sheets.v4.model.BatchUpdateValuesRequest;
import com.google.api.services.sheets.v4.model.Sheet;
import com.google.api.services.sheets.v4.model.Spreadsheet;
import com.google.api.services.sheets.v4.model.ValueRange;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class WebServer {

    private static final String SPREADSHEET_ID = "1mRJFDXOZ0MLSgAyf3XuDhinZAbMMXRJBlRE5G4iNfhw";
    private static Sheets sheetsService;
    private static final Gson gson = new Gson();

    private static final Map<String, List<List<Object>>> cache = new ConcurrentHashMap<>();
    private static final long SYNC_INTERVAL_MS = 30_000;

    private static final int OUTBOUND_CODE_COL = 0;
    private static final int OUTBOUND_QTY_COL = 1;
    private static final int INVENTORY_CODE_COL = 0;
    private static final int INVENTORY_QTY_COL = 4;

    public static void main(String[] args) throws Exception {
        sheetsService = buildSheetsService();
        startSyncThread();

        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", 8080), 0);

        // 首页
        server.createContext("/", exchange -> {
            InputStream is = WebServer.class.getResourceAsStream("/index.html");
            if (is == null) {
                send(exchange, 404, "text/plain; charset=UTF-8", "index.html 不存在");
                return;
            }
            byte[] bytes = is.readAllBytes();
            send(exchange, 200, "text/html; charset=UTF-8", new String(bytes, StandardCharsets.UTF_8));
        });

        // 工作表列表
        server.createContext("/api/sheets", exchange -> {
            try {
                Spreadsheet spreadsheet = sheetsService.spreadsheets().get(SPREADSHEET_ID).execute();
                List<Map<String, Object>> list = new ArrayList<>();
                for (Sheet sheet : spreadsheet.getSheets()) {
                    Map<String, Object> item = new HashMap<>();
                    item.put("title", sheet.getProperties().getTitle());
                    item.put("gid", sheet.getProperties().getSheetId());
                    list.add(item);
                }
                send(exchange, 200, "application/json; charset=UTF-8", gson.toJson(list));
            } catch (Exception e) {
                send(exchange, 500, "application/json; charset=UTF-8",
                        gson.toJson(Map.of("error", e.getMessage())));
            }
        });

        // 数据
        server.createContext("/api/data", exchange -> {
            try {
                Map<String, String> params = parseQuery(exchange);
                String sheetName = params.get("sheet");
                Integer maxRows = params.containsKey("maxRows") ? Integer.parseInt(params.get("maxRows")) : null;
                if (sheetName == null || sheetName.isEmpty()) {
                    send(exchange, 400, "application/json; charset=UTF-8",
                            gson.toJson(Map.of("error", "缺少 sheet 参数")));
                    return;
                }
                List<List<Object>> values = cache.get(sheetName);
                if (values == null) {
                    values = readSheet(sheetName);
                    cache.put(sheetName, values);
                }
                List<List<Object>> result = values;
                if (maxRows != null && result.size() > maxRows) {
                    result = new ArrayList<>(result.subList(0, maxRows));
                }
                send(exchange, 200, "application/json; charset=UTF-8", gson.toJson(result));
            } catch (Exception e) {
                send(exchange, 500, "application/json; charset=UTF-8",
                        gson.toJson(Map.of("error", e.getMessage())));
            }
        });

        // 手动同步
        server.createContext("/api/sync", exchange -> {
            try {
                Map<String, String> params = parseQuery(exchange);
                String sheetName = params.get("sheet");
                if (sheetName != null && !sheetName.isEmpty()) {
                    refreshCache(sheetName);
                } else {
                    syncAllCached();
                }
                send(exchange, 200, "application/json; charset=UTF-8",
                        gson.toJson(Map.of("success", true, "time", System.currentTimeMillis())));
            } catch (Exception e) {
                send(exchange, 500, "application/json; charset=UTF-8",
                        gson.toJson(Map.of("error", e.getMessage())));
            }
        });

        // 追加一行
        server.createContext("/api/write", exchange -> {
            try {
                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    send(exchange, 405, "application/json; charset=UTF-8",
                            gson.toJson(Map.of("error", "仅支持 POST")));
                    return;
                }
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Map<String, Object> payload = gson.fromJson(body, new TypeToken<Map<String, Object>>(){}.getType());
                String sheetName = (String) payload.get("sheet");
                Object rowObj = payload.get("row");
                if (sheetName == null || rowObj == null) {
                    send(exchange, 400, "application/json; charset=UTF-8",
                            gson.toJson(Map.of("error", "缺少 sheet 或 row")));
                    return;
                }
                @SuppressWarnings("unchecked")
                List<Object> row = (List<Object>) rowObj;
                ValueRange vRange = new ValueRange().setValues(Collections.singletonList(row));
                sheetsService.spreadsheets().values()
                        .append(SPREADSHEET_ID, sheetName + "!A1", vRange)
                        .setValueInputOption("RAW")
                        .setInsertDataOption("INSERT_ROWS")
                        .execute();
                refreshCache(sheetName);
                send(exchange, 200, "application/json; charset=UTF-8",
                        gson.toJson(Map.of("success", true)));
            } catch (Exception e) {
                send(exchange, 500, "application/json; charset=UTF-8",
                        gson.toJson(Map.of("error", e.getMessage())));
            }
        });

        // 单元格批量更新
        server.createContext("/api/update", exchange -> {
            try {
                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    send(exchange, 405, "application/json; charset=UTF-8",
                            gson.toJson(Map.of("error", "仅支持 POST")));
                    return;
                }
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Map<String, Object> payload = gson.fromJson(body, new TypeToken<Map<String, Object>>(){}.getType());
                String sheetName = (String) payload.get("sheet");
                Object updatesObj = payload.get("updates");
                if (sheetName == null || updatesObj == null) {
                    send(exchange, 400, "application/json; charset=UTF-8",
                            gson.toJson(Map.of("error", "缺少 sheet 或 updates")));
                    return;
                }
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> updates = (List<Map<String, Object>>) updatesObj;
                if (updates.isEmpty()) {
                    send(exchange, 400, "application/json; charset=UTF-8",
                            gson.toJson(Map.of("error", "updates 为空")));
                    return;
                }

                List<ValueRange> data = new ArrayList<>();
                for (Map<String, Object> u : updates) {
                    int row = ((Number) u.get("row")).intValue();
                    int col = ((Number) u.get("col")).intValue();
                    String value = u.get("value") == null ? "" : String.valueOf(u.get("value"));
                    String colLetter = columnIndexToLetter(col - 1);
                    String range = sheetName + "!" + colLetter + row;
                    data.add(new ValueRange().setRange(range)
                            .setValues(Collections.singletonList(Collections.singletonList(value))));
                }

                BatchUpdateValuesRequest req = new BatchUpdateValuesRequest();
                req.setValueInputOption("RAW");
                req.setData(data);
                sheetsService.spreadsheets().values().batchUpdate(SPREADSHEET_ID, req).execute();

                refreshCache(sheetName);
                send(exchange, 200, "application/json; charset=UTF-8",
                        gson.toJson(Map.of("success", true, "count", updates.size())));
            } catch (Exception e) {
                send(exchange, 500, "application/json; charset=UTF-8",
                        gson.toJson(Map.of("error", e.getMessage())));
            }
        });

        // 出库减数
        server.createContext("/api/outbound", exchange -> {
            try {
                Map<String, String> params = parseQuery(exchange);
                String outboundSheet = params.get("outboundSheet");
                String inventorySheet = params.get("inventorySheet");
                if (outboundSheet == null || inventorySheet == null) {
                    send(exchange, 400, "application/json; charset=UTF-8",
                            gson.toJson(Map.of("error", "缺少参数")));
                    return;
                }
                Map<String, Object> result = processOutbound(outboundSheet, inventorySheet);
                refreshCache(inventorySheet);
                send(exchange, 200, "application/json; charset=UTF-8", gson.toJson(result));
            } catch (Exception e) {
                send(exchange, 500, "application/json; charset=UTF-8",
                        gson.toJson(Map.of("error", e.getMessage())));
            }
        });

        server.setExecutor(null);
        server.start();
        System.out.println("服务已启动：http://localhost:8080（监听 0.0.0.0）");
        System.out.println("定时同步间隔：" + (SYNC_INTERVAL_MS / 1000) + " 秒");
    }

    private static void startSyncThread() {
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(SYNC_INTERVAL_MS);
                    syncAllCached();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    System.err.println("定时同步出错：" + e.getMessage());
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private static void syncAllCached() {
        for (String sheetName : new ArrayList<>(cache.keySet())) {
            try {
                List<List<Object>> fresh = readSheet(sheetName);
                List<List<Object>> old = cache.get(sheetName);
                if (!Objects.equals(old, fresh)) {
                    cache.put(sheetName, fresh);
                    System.out.println("缓存已更新：" + sheetName + "（" + fresh.size() + " 行）");
                }
            } catch (Exception e) {
                System.err.println("同步 " + sheetName + " 失败：" + e.getMessage());
            }
        }
    }

    private static void refreshCache(String sheetName) {
        try {
            cache.put(sheetName, readSheet(sheetName));
        } catch (Exception e) {
            System.err.println("刷新缓存失败 " + sheetName + "：" + e.getMessage());
        }
    }

    private static List<List<Object>> readSheet(String sheetName) throws IOException {
        String range = sheetName + "!A1:Z2000";
        ValueRange response = sheetsService.spreadsheets().values()
                .get(SPREADSHEET_ID, range)
                .execute();
        List<List<Object>> values = response.getValues();
        return values == null ? new ArrayList<>() : values;
    }

    private static Map<String, Object> processOutbound(String outboundSheet, String inventorySheet) throws IOException {
        List<List<Object>> outboundRows = readSheet(outboundSheet);
        List<List<Object>> inventoryRows = readSheet(inventorySheet);

        if (outboundRows.size() < 2) {
            return Map.of("success", false, "message", "出库表没有数据行");
        }

        Map<String, Integer> inventoryIndex = new HashMap<>();
        for (int i = 1; i < inventoryRows.size(); i++) {
            List<Object> row = inventoryRows.get(i);
            if (row.size() > INVENTORY_CODE_COL) {
                String code = String.valueOf(row.get(INVENTORY_CODE_COL)).trim();
                if (!code.isEmpty()) inventoryIndex.put(code, i + 1);
            }
        }

        List<Map<String, Object>> details = new ArrayList<>();
        int successCount = 0, failCount = 0;

        for (int i = 1; i < outboundRows.size(); i++) {
            List<Object> row = outboundRows.get(i);
            if (row.size() <= Math.max(OUTBOUND_CODE_COL, OUTBOUND_QTY_COL)) continue;
            String code = String.valueOf(row.get(OUTBOUND_CODE_COL)).trim();
            if (code.isEmpty()) continue;

            int qty;
            try {
                qty = (int) Double.parseDouble(String.valueOf(row.get(OUTBOUND_QTY_COL)).replace(",", "").trim());
            } catch (Exception e) {
                details.add(Map.of("code", code, "status", "跳过", "reason", "数量无效"));
                failCount++;
                continue;
            }

            Integer invRowNum = inventoryIndex.get(code);
            if (invRowNum == null) {
                details.add(Map.of("code", code, "qty", qty, "status", "失败", "reason", "库存表中找不到"));
                failCount++;
                continue;
            }

            int invIdx = invRowNum - 1;
            List<Object> invRow = inventoryRows.get(invIdx);
            int currentQty;
            try {
                currentQty = (int) Double.parseDouble(String.valueOf(invRow.get(INVENTORY_QTY_COL)).replace(",", "").trim());
            } catch (Exception e) {
                currentQty = 0;
            }

            int newQty = currentQty - qty;
            if (newQty < 0) {
                details.add(Map.of("code", code, "qty", qty, "current", currentQty,
                        "status", "失败", "reason", "库存不足（当前 " + currentQty + "）"));
                failCount++;
                continue;
            }

            String colLetter = columnIndexToLetter(INVENTORY_QTY_COL);
            String cellRange = inventorySheet + "!" + colLetter + invRowNum;
            ValueRange body = new ValueRange().setValues(
                    Collections.singletonList(Collections.singletonList(newQty)));
            sheetsService.spreadsheets().values()
                    .update(SPREADSHEET_ID, cellRange, body)
                    .setValueInputOption("RAW")
                    .execute();

            details.add(Map.of("code", code, "qty", qty, "before", currentQty, "after", newQty, "status", "成功"));
            successCount++;
        }

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("successCount", successCount);
        result.put("failCount", failCount);
        result.put("details", details);
        return result;
    }

    private static String columnIndexToLetter(int index) {
        StringBuilder sb = new StringBuilder();
        index++;
        while (index > 0) {
            int rem = (index - 1) % 26;
            sb.insert(0, (char) ('A' + rem));
            index = (index - 1) / 26;
        }
        return sb.toString();
    }

    private static Map<String, String> parseQuery(HttpExchange exchange) {
        Map<String, String> map = new HashMap<>();
        String query = exchange.getRequestURI().getQuery();
        if (query == null) return map;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                map.put(kv[0], URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
            }
        }
        return map;
    }

    private static Sheets buildSheetsService() throws Exception {
        InputStream credentialsStream = WebServer.class.getResourceAsStream("/service-account.json");
        if (credentialsStream == null) {
            throw new RuntimeException("找不到 service-account.json");
        }
        GoogleCredentials credentials = GoogleCredentials.fromStream(credentialsStream)
                .createScoped(Collections.singleton(SheetsScopes.SPREADSHEETS));
        return new Sheets.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                GsonFactory.getDefaultInstance(),
                new HttpCredentialsAdapter(credentials))
                .setApplicationName("KCsheets")
                .build();
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}