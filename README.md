## Comparing Spring MVC + Tomcat with Spring Webflux (Reactor) + Netty

This is a starter project for comparing implementation and performance of the two different stacks now available 
for Spring 5 platform.
 
 * The traditional stack: Spring MVC running on Tomcat
 * The reactive stack: Webflux (Reactor) running on Netty
 
Obviously, and I am well aware of this, the implementation choices I have made are highly _opinionated_. There are several
improvements which could be made for each stack. And this is exactly the point, this project is intended as a starting point
to be forked and tweaked as appropriate for specific scenarios or measurements. 
 
### Use-case and implementation details 
 
The idea was to take a simple use-case inspired by the one described in
[External Service Call](https://spring.io/blog/2016/06/07/notes-on-reactive-programming-part-i-the-reactive-landscape) and
implement it in two stacks with a minimum of customization, i. e. using sensible defaults wherever is possible.

It is a simple REST gateway listening for a single GET request which scatters onto a 10 parallel REST calls to an external API,
then gathers the results into the response to the original call.
I've used a simple GET request to [https://postman-echo.com/get?key=number](https://docs.postman-echo.com/#078883ea-ac9e-842e-8f41-784b59a33722)
as the endpoint to the external system.

Particularly I was interested into the response time and the number of threads required by each setup.

````text
Note: the runtime is Spring Boot v. 2.0.0.M3 on JVM 1.8.0_141, Windows 7 (64 bit).  
````

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
Notice that I'm using standard [HttpClient](https://hc.apache.org/httpcomponents-client-ga/) to manage a pool of HTTP connections.

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
customizations to the minimum. The interesting points are as follows.
 
 1. I use a default implementation of ``org.apache.http.impl.client.CloseableHttpClient`` with will in turn set up an
 instance of ``org.apache.http.impl.conn.PoolingHttpClientConnectionManager`` to perform connection pooling.
 2. I need to parse the JSON returned by each call to Postman API. For that I simply use ``com.fasterxml.jackson.databind.ObjectMapper``
 with a simple POJO for this purpose.
 3. The parallelism is implemented via a "work-stealing" ``java.util.concurrent.ExecutorService`` pooling as many threads
 as there are processors available. It needs to be explicitly configured to wait for all the threads to complete before
 the results can be gathered for the final response.
 4. The results of the concurrent calls are collected into a shared instance of ``java.util.concurrent.ConcurrentHashMap``.
 
 ### Webflux (Reactor) + Netty
 
 Maven dependencies:
 
 ````xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webflux</artifactId>
</dependency>
```` 

Notice that this single dependency is the only one we need as we are going to take advantage of the non-blocking
``org.springframework.web.reactive.function.client.WebClient`` for performing HTTP connections.

Controller:

````java
@Configuration
public class ReactiveController {
    private final WebClient postmanClient = WebClient.builder().baseUrl("https://postman-echo.com").build();

    @Bean
    public RouterFunction<ServerResponse> routerFunction() {
        return route(GET("/parallel"),
                request -> ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(parallel(), String.class))
                ;
    }

    private Publisher<String> parallel() {
        return Flux.range(1, 10)
                .map(i -> Integer.toString(i))
                .flatMap(this::postmanGet)
                .log()
                .collect(Collectors.joining());
    }

    private Mono<String> postmanGet(String value) {
        return postmanClient.get()
                .uri("/get?key=" + value)
                .exchange()
                .flatMap(response -> response
                        .bodyToMono(PostmanGetResponse.class)
                        .map(p -> p.getArgs().get("key").toString()))
                ;
    }
}
````

The interesting points are as follows:

1. I rely on the default implementation of ``org.springframework.web.reactive.function.client.WebClient`` to manage connection pooling.
2. The parallelism comes from the use of [flatMap](https://projectreactor.io/docs/core/release/api/reactor/core/publisher/Flux.html#flatMap-java.util.function.Function-)
operator.
3. The gathering of the results is done via ``collect()`` operator.

### Performance analysis

Once the two applications were up and running I've issued the following command for testing.

````console
$ curl -s -w "\n%{time_total}\n" http://localhost:9090/parallel
````

With the following results for the total request times.

|Run|Standard|Reactive|
|---|--------|--------|
| 1 | 1.591  | 1.185  |
| 2 | 0.515  | 0.171  |
| 3 | 0.515  | 0.156  |
| 4 | 0.515  | 0.156  |
| 5 | 0.593  | 0.172  |
  
Interestingly, using the standard sack the result seems to be always assembled in order: i.e. ``123456789``, where as using 
reactive stack the results are assembled in random order: i.e. ``14251037698```.

Below are the screenshots of the JConsole inspections of the applications.

_Standard_

![MVC + Tomcat](https://github.com/gushakov/reactor-compare/blob/master/standard.png)

_Reactive_

![Reactor + Netty](https://github.com/gushakov/reactor-compare/blob/master/reactive.png)


## Discussion

Implementing the chosen use-case with the standard stack is a more involved (complicated) when comparing to the reactive stack. 
Consider the following points:

 1. There is an explicit need to set up a dependency for ```httpclient```.
 2. Conversion from the JSON response from Postman API needs to be done manually (using Jackson ``com.fasterxml.jackson.databind.ObjectMapper``).
 3. Task executor needs to be set up explicitly for parallelism, which involves a choice among several options: fixed, cached, work-stealing pool.
 Also one needs to be careful to shutdown the pool after all tasks were executed.
 4. Gathering results maybe tricky: one needs to explicitly share a (synchronized) container between the threads.
 5. In general there is more amount of code using the standard stack with imperative style versus the reactive stack using functional style of programming.
 
Looking now at the performance of the two stacks (for **this simple use-case** only):

 1. Reactive stack is 3-4 times faster.
 2. The number of live threads is smaller when using the reactive stack (23), comparing to the standard stack (28, with periodic peaks to up to 36).
 3. Other parameters (memory usage, processor, number of classes loaded, size of the artifact) are almost the same. 