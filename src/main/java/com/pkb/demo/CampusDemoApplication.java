package com.pkb.demo;

import com.pkb.campus.CampusDemoController;
import com.pkb.campus.CampusService;
import com.pkb.campus.MockCampusDataProvider;
import com.pkb.mcp.CampusMcpConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Import;

/** Standalone fictional-campus showcase; no database or model service is needed. */
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
@Import({MockCampusDataProvider.class, CampusService.class,
        CampusDemoController.class, CampusMcpConfig.class})
public class CampusDemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(CampusDemoApplication.class, args);
    }
}
