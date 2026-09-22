package de.agiehl;

import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
@EnableConfigurationProperties(AlcoProperties.class)
public class StvAlcoDownloaderApplication {

    public static void main(String[] args) {
        var application = new SpringApplication(StvAlcoDownloaderApplication.class);
        application.setBannerMode(Banner.Mode.OFF);
        application.setWebApplicationType(WebApplicationType.NONE);
        ConfigurableApplicationContext context = application.run(args);
        int exitCode = SpringApplication.exit(context);
        System.exit(exitCode);
    }
}
