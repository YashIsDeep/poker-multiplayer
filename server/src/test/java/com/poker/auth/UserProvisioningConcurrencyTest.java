package com.poker.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.poker.db.user.UserAccount;
import com.poker.db.user.UserAccountRepository;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@AutoConfigureMockMvc
class UserProvisioningConcurrencyTest {
    private static final String DATABASE = "provisioning_race_"
            + UUID.randomUUID().toString().replace("-", "");

    @Autowired
    private UserProvisioningService provisioningService;

    @Autowired
    private UserAccountRepository repository;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:" + DATABASE + ";DB_CLOSE_DELAY=-1");
    }

    @Test
    void concurrentFirstLoginsConvergeOnOneUserThroughTheNamedConstraint() throws Exception {
        ExternalIdentity identity = new ExternalIdentity(
                AuthProvider.GOOGLE, "same-first-login", "Alice", null);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<UserAccount> first = submit(executor, ready, start, identity);
            Future<UserAccount> second = submit(executor, ready, start, identity);
            ready.await();
            start.countDown();

            UserAccount firstResult = first.get();
            UserAccount secondResult = second.get();
            assertEquals(firstResult.getId(), secondResult.getId());
            assertEquals(1, repository.count());
        } finally {
            executor.shutdownNow();
        }
    }

    private Future<UserAccount> submit(
            ExecutorService executor,
            CountDownLatch ready,
            CountDownLatch start,
            ExternalIdentity identity) {
        return executor.submit(() -> {
            ready.countDown();
            start.await();
            return provisioningService.provision(identity);
        });
    }
}
