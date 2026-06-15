package de.example.vk.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;

/**
 * Datenquelle:
 *
 *   VK_DB_MODE=oracle  -> Oracle 19c ueber VK_DB_URL / VK_DB_USER / VK_DB_PASSWORD
 *   VK_DB_MODE=h2      -> eingebettete H2 im Oracle-Kompatibilitaetsmodus (Dev/Demo,
 *                         Schema + Testdaten werden beim Start automatisch angelegt)
 */
@Configuration
@EnableTransactionManagement
public class DataSourceConfig {

    @Bean(destroyMethod = "close")
    public HikariDataSource dataSource(
            @Value("${VK_DB_MODE:h2}") String mode,
            @Value("${VK_DB_URL:}") String url,
            @Value("${VK_DB_USER:}") String user,
            @Value("${VK_DB_PASSWORD:}") String password) {

        HikariConfig config = new HikariConfig();
        if ("oracle".equalsIgnoreCase(mode)) {
            config.setDriverClassName("oracle.jdbc.OracleDriver");
            config.setJdbcUrl(url);
            config.setUsername(user);
            config.setPassword(password);
            config.setMaximumPoolSize(20);
        } else {
            // driverClassName explizit setzen: im Servlet-Container (Tomcat) registriert
            // sich der Treiber sonst nicht automatisch beim DriverManager ("No suitable driver").
            config.setDriverClassName("org.h2.Driver");
            config.setJdbcUrl("jdbc:h2:mem:vk;MODE=Oracle;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE");
            config.setUsername("sa");
            config.setPassword("");
            config.setMaximumPoolSize(10);
        }
        config.setPoolName("vk-pool");
        return new HikariDataSource(config);
    }

    @Bean
    public PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    public NamedParameterJdbcTemplate namedParameterJdbcTemplate(DataSource dataSource) {
        return new NamedParameterJdbcTemplate(dataSource);
    }
}
