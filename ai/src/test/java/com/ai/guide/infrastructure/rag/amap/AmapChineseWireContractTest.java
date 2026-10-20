package com.ai.guide.infrastructure.rag.amap;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AmapChineseWireContractTest {
    @Test void everyChineseQueryBuilderProducesAsciiUriAndPreservesDecodedValues() {
        var client = new AmapWebServiceClient("http://127.0.0.1:1","fixture");
        var uris = List.of(client.buildPoiTextRequest("测试馆 + 50% & 分馆#%20","测试市"),
                client.buildPoiAroundRequest("120,30",1000,"风景名胜","测试景点","navi"),
                client.buildPoiDetailRequest("测试编号"),client.buildGeocodeRequest("测试市政府","测试市"),
                client.buildTransitRequest("120,30","120.01,30","023","023","0","poi-a","poi-b"),
                client.buildTransitFallbackRequest("120,30","120.01,30","测试市"),
                client.buildInputTipsRequest("测试馆","测试市"),client.buildWeatherRequest("测试市"));
        for (URI uri : uris) assertTrue(uri.toString().chars().allMatch(c -> c < 128),uri.toString());
        assertEquals("测试馆 + 50% & 分馆#%20",query(uris.getFirst()).get("keywords"));
        assertEquals("测试市",query(uris.getFirst()).get("city"));
    }
    @Test void socketCapturesAsciiRequestLineWithUtf8EncodedChineseAndLiteralReservedCharacters() throws Exception {
        try(var server = new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(3000);
            var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                var captured = pool.submit(() -> {
                    try(var socket=server.accept()) {
                        socket.setSoTimeout(3000);
                        var input=socket.getInputStream(); var line=new java.io.ByteArrayOutputStream();
                        int b; while((b=input.read())!=-1 && b!='\n') line.write(b);
                        // Drain request headers before writing a valid JSON response.
                        var reader=new java.io.BufferedReader(new java.io.InputStreamReader(input,StandardCharsets.US_ASCII));
                        String header; while((header=reader.readLine())!=null&&!header.isEmpty()) {}
                        socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 14\r\nConnection: close\r\n\r\n{\"status\":\"1\"}".getBytes(StandardCharsets.US_ASCII));
                        socket.getOutputStream().flush(); return line.toByteArray();
                    }
                });
                var client=new AmapWebServiceClient("http://127.0.0.1:"+server.getLocalPort(),"fixture");
                String keyword="测试馆 + 50% & 分馆#%20";
                assertEquals("1",client.get(client.buildPoiTextRequest(keyword,"测试市")).path("status").asText());
                byte[] bytes=captured.get(3,java.util.concurrent.TimeUnit.SECONDS);
                for(byte b:bytes) assertTrue((b&0xff)<128,"wire request line must be ASCII");
                String line=new String(bytes,StandardCharsets.US_ASCII);
                URI received=URI.create(line.split(" ",3)[1]);
                assertTrue(received.getRawQuery().contains("%E6%B5%8B"),line);
                assertEquals(keyword,query(received).get("keywords"));
            } finally { pool.shutdownNow(); }
        }
    }
    @Test void restTemplateUriOverloadSendsChineseQueryEncodedOnActualLoopbackHttp() throws Exception {
        var received = new AtomicReference<URI>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v5/place/text",exchange -> {
            received.set(exchange.getRequestURI());
            byte[] json = "{\"status\":\"1\",\"pois\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,json.length);
            try(var body=exchange.getResponseBody()) { body.write(json); }
        });
        server.start();
        try {
            var client = new AmapWebServiceClient("http://127.0.0.1:"+server.getAddress().getPort(),"fixture");
            assertEquals("1",client.get(client.buildPoiTextRequest("测试起点","测试市")).path("status").asText());
            assertNotNull(received.get());
            assertTrue(received.get().getRawQuery().chars().allMatch(c -> c < 128));
            assertEquals("测试起点",query(received.get()).get("keywords"));
        } finally { server.stop(0); }
    }
    private static Map<String,String> query(URI uri) {
        var values = new HashMap<String,String>();
        for(String pair:uri.getRawQuery().split("&")) { String[] kv=pair.split("=",2); values.put(URLDecoder.decode(kv[0],StandardCharsets.UTF_8),URLDecoder.decode(kv[1],StandardCharsets.UTF_8)); }
        return values;
    }
}
