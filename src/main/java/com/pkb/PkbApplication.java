package com.pkb;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import com.pkb.demo.CampusDemoApplication;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@SpringBootApplication
@ConfigurationPropertiesScan
@ComponentScan(excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE, classes = CampusDemoApplication.class))
public class PkbApplication {

    public static void main(String[] args) {
        SpringApplication.run(PkbApplication.class, args);
    }

    /** 承载 SSE 问答的异步线程池（流式生成期间不占用 Tomcat 工作线程） */
    @Bean
    public Executor chatExecutor() {
        return Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "rag-chat");
            t.setDaemon(true);
            return t;
        });
    }
}
