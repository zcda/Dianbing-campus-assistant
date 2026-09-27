package com.dianbing.demo;

import com.dianbing.campus.CampusDemoController;
import com.dianbing.campus.CampusService;
import com.dianbing.campus.MockCampusDataProvider;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Import;

/** Standalone fictional-campus showcase; no database or model service is needed. */
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
@Import({MockCampusDataProvider.class, CampusService.class,
        CampusDemoController.class})
public class CampusDemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(CampusDemoApplication.class, args);
    }
}
