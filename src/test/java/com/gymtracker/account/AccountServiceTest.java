package com.gymtracker.account;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gymtracker.common.ForbiddenException;
import java.time.Clock;
import org.junit.jupiter.api.Test;

class AccountServiceTest {

    @Test
    void registrationIsClosedWithoutAnInviteCode() {
        AccountService service = new AccountService(null, null, Clock.systemUTC(), "  ");
        assertThatThrownBy(() -> service.register("Janet", "new@example.com", "long-enough", ""))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Registration is closed");
    }
}
