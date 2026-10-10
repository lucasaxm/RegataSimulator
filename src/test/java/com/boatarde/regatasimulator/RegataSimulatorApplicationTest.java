package com.boatarde.regatasimulator;

import com.boatarde.regatasimulator.bots.RegataSimulatorBot;
import com.boatarde.regatasimulator.configuration.TelegramBotRegistration;
import io.jsondb.JsonDBTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RegataSimulatorApplicationTest {

    @Mock private RegataSimulatorBot bot;
    @Mock private JsonDBTemplate database;
    @Mock private TelegramBotRegistration registration;

    @Test
    void initializesCollectionsWithoutRegisteringWhenDisabled() {
        new RegataSimulatorApplication(bot, database, registration, false).onStartUpInit();

        verifyNoInteractions(registration, bot);
        for (String collection : new String[]{"users", "templates", "sources", "memes"}) {
            verify(database).createCollection(collection);
        }
    }

    @Test
    void registersAndRetainsExistingCollectionsWhenEnabled() throws Exception {
        for (String collection : new String[]{"users", "templates", "sources", "memes"}) {
            when(database.collectionExists(collection)).thenReturn(true);
        }

        new RegataSimulatorApplication(bot, database, registration, true).onStartUpInit();

        verify(registration).register(bot);
        for (String collection : new String[]{"users", "templates", "sources", "memes"}) {
            verify(database, org.mockito.Mockito.never()).createCollection(collection);
        }
    }

    @Test
    void initializesStorageBeforeRegistrationAndRefusesFailedStartupWithoutLeakingErrors() throws Exception {
        doThrow(new TelegramApiException("synthetic registration failure")).when(registration).register(bot);
        var failure=org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
            () -> new RegataSimulatorApplication(bot, database, registration, true).onStartUpInit());
        org.junit.jupiter.api.Assertions.assertNull(failure.getCause());
        var order=org.mockito.Mockito.inOrder(database,registration);
        for(String name:new String[]{"users","templates","sources","memes"}) {
            order.verify(database).collectionExists(name); order.verify(database).createCollection(name);
        }
        order.verify(registration).register(bot);
    }
}