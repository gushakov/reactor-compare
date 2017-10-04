package ch.unil.reactor.compare.netty;

import ch.unil.reactor.compare.common.PostmanGetResponse;
import org.reactivestreams.Publisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.stream.Collectors;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;
import static org.springframework.web.reactive.function.server.ServerResponse.ok;

/**
 * @author gushakov
 */
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
