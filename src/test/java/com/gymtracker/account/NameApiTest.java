package com.gymtracker.account;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gymtracker.IntegrationTestBase;
import com.gymtracker.TestUsers;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class NameApiTest extends IntegrationTestBase {

    @Test
    void accountWithoutANameHasNone() throws Exception {
        apiGet("/api/me")
                .andExpect(jsonPath("$.email").value(TestUsers.EMAIL))
                .andExpect(jsonPath("$.name").doesNotExist());
    }

    @Test
    void changesTheNameTrimmed() throws Exception {
        apiPut("/api/me/name", "{\"name\": \" Janet  \"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Janet"));
        apiGet("/api/me").andExpect(jsonPath("$.name").value("Janet"));
    }

    @Test
    void nameIsRequiredAndAtMost40Characters() throws Exception {
        apiPut("/api/me/name", "{\"name\": \"   \"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Enter your name"));
        apiPut("/api/me/name", "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Enter your name"));
        apiPut("/api/me/name", "{\"name\": \"%s\"}".formatted("a".repeat(41)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Use at most 40 characters for your name"));
        apiPut("/api/me/name", "{\"name\": \"%s\"}".formatted("😀".repeat(40))).andExpect(status().isOk());
    }

    @Test
    void onlyChangesTheSignedInAccount() throws Exception {
        createUser("other@example.com");
        apiPut("/api/me/name", "{\"name\": \"Janet\"}").andExpect(status().isOk());
        actingAs = "other@example.com";
        apiGet("/api/me").andExpect(jsonPath("$.name").doesNotExist());
    }

    @Test
    void needsSigningIn() throws Exception {
        mvc.perform(put("/api/me/name").with(xsrf()).contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"Janet\"}"))
                .andExpect(status().isUnauthorized());
    }
}
