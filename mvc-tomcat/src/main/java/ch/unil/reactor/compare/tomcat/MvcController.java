package ch.unil.reactor.compare.tomcat;

import ch.unil.reactor.compare.common.PostmanGetResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * @author gushakov
 */
@RestController
public class MvcController {

    private final CloseableHttpClient httpClient = HttpClients.createDefault();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @RequestMapping(path = "/parallel", method = RequestMethod.GET, produces = MediaType.TEXT_PLAIN_VALUE)
    public String parallel() throws InterruptedException {

        final Map<String, String> result = new ConcurrentHashMap<>();

        // based on code from https://stackoverflow.com/a/1250655/8516495

//        final ExecutorService taskExecutor = Executors.newFixedThreadPool(8);
//        final ExecutorService taskExecutor = Executors.newCachedThreadPool();
        final ExecutorService taskExecutor = Executors.newWorkStealingPool();

        IntStream.range(1, 10).forEach(i -> taskExecutor.execute(() -> {
            try (final CloseableHttpResponse response = httpClient.execute(new HttpGet("https://postman-echo.com/get?key=" + i))) {
                final String value = (String) objectMapper.readValue(response.getEntity().getContent(), PostmanGetResponse.class)
                        .getArgs().get("key");
                result.put(value, value);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }));

        taskExecutor.shutdown();
        taskExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.SECONDS);

        return result.keySet().stream().collect(Collectors.joining());

    }

}
