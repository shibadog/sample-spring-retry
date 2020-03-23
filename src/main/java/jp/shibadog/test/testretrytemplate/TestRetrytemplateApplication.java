package jp.shibadog.test.testretrytemplate;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.cloud.client.circuitbreaker.ConfigBuilder;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryOperations;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.retry.annotation.Retryable;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestOperations;
import org.springframework.web.client.RestTemplate;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;

@SpringBootApplication
@EnableRetry
public class TestRetrytemplateApplication {

    public static void main(String[] args) {
        SpringApplication.run(TestRetrytemplateApplication.class, args);
    }

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder.build();
    }
    
    @Bean
    public Customizer<Resilience4JCircuitBreakerFactory> defaultCustomizer() {
        return factory -> factory.configureDefault(id -> new Resilience4JConfigBuilder(id)
                .timeLimiterConfig(TimeLimiterConfig.custom().timeoutDuration(Duration.ofSeconds(10)).build())
                .circuitBreakerConfig(CircuitBreakerConfig.ofDefaults())
                .build());
    }

    @Bean
    public RetryTemplate retryTemplate() {
        RetryTemplate retryTemplate = new RetryTemplate();
        SimpleRetryPolicy policy = createPoricy();
        retryTemplate.setRetryPolicy(policy);
        return retryTemplate;
    }

    SimpleRetryPolicy createPoricy() {
        Map<Class<? extends Throwable>, Boolean> retryableExceptions = 
            Collections.singletonMap(RuntimeException.class, true);
        return new SimpleRetryPolicy(3, retryableExceptions);
    }

    @RestController
    public static class Controller<CONF, CONFB extends ConfigBuilder<CONF>> {

        private final RestTemplate restTemplate;
        private final RequestService<CONF, CONFB> service;
        public Controller(RestTemplate restTemplate, RequestService<CONF, CONFB> service) {
            this.restTemplate = restTemplate;
            this.service = service;
        }
    
        @RequestMapping("/sleep/{isError}")
        public String sleep(@PathVariable("isError") boolean isError) throws InterruptedException {
            if (isError) throw new RuntimeException("エラーやで");
            TimeUnit.SECONDS.sleep(5L);
            return "OK";
        }
    
        @RequestMapping("/retry")
        @Retryable(value = {RuntimeException.class})
        public String retry() {
            Boolean randamBool = ThreadLocalRandom.current().nextInt() % 2 == 0;
            return restTemplate.getForObject("http://localhost:8080/sleep/" + randamBool, String.class);
        }
        
    
        @RequestMapping("/retry-for-service")
        public String retryForService() {
            return service.request();
        }
 
        @RequestMapping("/retry-for-service2")
        public String retryForService2() {
            return service.request2();
        }

        @RequestMapping("/circuit-breaker")
        public String retryAndCircuitBreaker() {
            return service.requestForCircuitBreaker();
        }
    }

    @Service
    public static class RequestService<CONF, CONFB extends ConfigBuilder<CONF>> {
        private final RestOperations restTemplate;
        private final CircuitBreaker cb;
        private final RetryOperations retryTemplate;
        public RequestService(RestOperations restTemplate, CircuitBreakerFactory<CONF, CONFB> cbFactory, RetryOperations retryTemplate) {
            this.restTemplate = restTemplate;
            this.cb = cbFactory.create("test");
            this.retryTemplate = retryTemplate;
        }

        @Retryable(value = {RuntimeException.class})
        public String request() {
            Boolean randomBool = ThreadLocalRandom.current().nextInt() % 2 == 0;
            return restTemplate.getForObject("http://localhost:8080/sleep/" + randomBool, String.class);
        }

        public String request2() {
            return retryTemplate.execute(this::exchange);
        }

        public String requestForCircuitBreaker() {
            return cb.run(() -> {
                // Boolean randomBool = ThreadLocalRandom.current().nextInt() % 2 == 0;
                return restTemplate.getForObject("http://localhost:8080/sleep/" + true, String.class);
            }, t -> {
                return "fallback!";
            });
        }

        public String requestForCircuitBreakerAndRetry() {
            return cb.run(() -> {
                return retryTemplate.execute(this::exchange);
            }, t -> {
                return "fallback!";
            });
        }

        String exchange(RetryContext context) {
            Boolean randomBool = ThreadLocalRandom.current().nextInt() % 2 == 0;
            return restTemplate.getForObject("http://localhost:8080/sleep/" + randomBool, String.class);
        }
    }
}
