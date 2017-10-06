## Comparing Spring MVC + Tomcat with Spring Webflux (Reactor) + Netty

This is a starter project for comparing implementation and performance of the two different stacks
now available for Spring 5 platform.
 * The traditional stack: Spring MVC running on Tomcat
 * The reactive stack: Webflux (Reactor) running on Netty
 
### Use-case and implementation details 
 
The idea was to take a simple use-case inspired by the one described in
[External Service Call](https://spring.io/blog/2016/06/07/notes-on-reactive-programming-part-i-the-reactive-landscape) and
implement it in two stacks with a minimum of customization, i. e. using sensible defaults wherever is possible.

It is a simple REST gateway listening for a single GET request which scatters onto a 10 parallel REST calls to an external API,
then gathers the results into the response to the original call.
I've used a simple GET request to [https://postman-echo.com/get?key=number](https://docs.postman-echo.com/#078883ea-ac9e-842e-8f41-784b59a33722)
as the endpoint to the external system.

Particularly I was interested into the response time and the number of threads required by each setup.

Note: the runtime is Spring Boot v. 2.0.0.M3 on JVM 1.8.0_141, Windows 7 (64 bit).  

Here are the implementation details for either stack.

### MVC + Tomcat

Maven dependencies: 

````xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>

<dependency>
    <groupId>org.apache.httpcomponents</groupId>
    <artifactId>httpclient</artifactId>
</dependency>
````
Notice that we are using the standard [HttpClient](https://hc.apache.org/httpcomponents-client-ga/) since for this kind of work
we certainly need an HTTP client capable of managing a pool of connections.

Controller:

````java
@RestController
public class MvcController {

    private final CloseableHttpClient httpClient = HttpClients.createDefault();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @RequestMapping(path = "/parallel", method = RequestMethod.GET, produces = MediaType.TEXT_PLAIN_VALUE)
    public String parallel() throws InterruptedException {

        final Map<String, String> result = new ConcurrentHashMap<>();

        // based on code from https://stackoverflow.com/a/1250655/8516495

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
````

This code was designed to resemble in technical characteristics to the reactive one (see below) all the while keeping the
customisations to the minimum. The interesting points are as follows.
 
 1. We use a default implementation of ``org.apache.http.impl.client.CloseableHttpClient`` with will in turn set up an
 instance of ``org.apache.http.impl.conn.PoolingHttpClientConnectionManager`` for us.
 2. We need to parse the JSON returned by each call to Postman API. We use ``com.fasterxml.jackson.databind.ObjectMapper``
 with a simple POJO for this purpose.
 3. The parallelism is implemented via a "work-stealing" ``java.util.concurrent.ExecutorService`` pooling as many threads
 as there are processors available.