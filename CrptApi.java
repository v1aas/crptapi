import com.google.gson.Gson;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;


@Slf4j
public class CrptApi {
    private final long rateToMillis;
    private final int requestLimit;
    private final AtomicInteger availableRequests;
    private long nextRefillTime;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition condition = lock.newCondition();
    private final HttpRequest.Builder builder = HttpRequest.newBuilder();
    private final HttpClient client = HttpClient.newHttpClient();
    private final URI uri = URI.create("https://ismp.crpt.ru/api/v3/lk/documents/create");

    public CrptApi(TimeUnit timeUnit, int requestLimit) {
        this.rateToMillis = timeUnit.toMillis(1);
        this.requestLimit = requestLimit;
        this.availableRequests = new AtomicInteger(requestLimit);
        this.nextRefillTime = System.currentTimeMillis() + this.rateToMillis;
    }


    // Main сделан для тестирования функциональности работы в целом и в потоках
    public static void main(String[] args) {
        final CrptApi crptApi = new CrptApi(TimeUnit.MINUTES, 2);
        int numberOfThreads = 5;
        ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);

        for (int i = 0; i < numberOfThreads; i++) {
            executor.submit(() -> {
                Document document = new Document();
                String signature = "";
                crptApi.createDocument(document, signature);
            });
        }

        executor.shutdown();
        try {
            if (!executor.awaitTermination(120, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public void createDocument(Document document, String signature) {
        if (tryRequest()) {
            sendPostRequest(document, signature);
        }
    }

    public void sendPostRequest(Document document, String signature) {
        try {
            Gson gson = new Gson();
            String json = gson.toJson(document);
            HttpRequest request = builder
                    .POST(BodyPublishers.ofString(json))
                    .uri(uri)
                    .header("Content-Type", "*/*")
                    .header("Signature", signature)
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            log.info("{} {}", response.statusCode(), response.body());
        } catch (IOException | InterruptedException e) {
            log.error("Error sending POST request", e);
            throw new RuntimeException(e);
        }
    }

    public boolean tryRequest() {
        long currentTime = System.currentTimeMillis();
        lock.lock();
        try {
            while (currentTime < nextRefillTime && availableRequests.get() <= 0) {
                // Ожидаем, пока не наступит время для следующего пополнения запросов
                // или пока другой поток не освободит место, выполнив запрос
                long time = nextRefillTime - currentTime;
                log.info("Too many request, the next request will be available in {} ms", time);
                condition.await(time, TimeUnit.MILLISECONDS);
                currentTime = System.currentTimeMillis();
            }
            if (currentTime > nextRefillTime) {
                availableRequests.set(requestLimit);
                nextRefillTime = currentTime + rateToMillis;
            }

            if (availableRequests.get() > 0) {
                availableRequests.decrementAndGet();
                condition.signalAll();
                return true;
            }
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }
    }

    @Data
    private static class Document {
        @Data
        static class Description {
            private String participantInn;
        }

        @Data
        static class Product {
            private String certificate_document;
            private String certificate_document_date;
            private String certificate_document_number;
            private String owner_inn;
            private String producer_inn;
            private String production_date;
            private String tnved_code;
            private String uit_code;
            private String uitu_code;
        }

        private Description description;
        private String doc_id;
        private String doc_status;
        private String doc_type;
        private boolean importRequest;
        private String owner_inn;
        private String participant_inn;
        private String producer_inn;
        private String production_date;
        private String production_type;
        private List<Product> products;
        private String reg_date;
        private String reg_number;
    }
}
