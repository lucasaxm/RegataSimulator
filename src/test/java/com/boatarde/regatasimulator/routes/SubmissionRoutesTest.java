package com.boatarde.regatasimulator.routes;

import com.boatarde.regatasimulator.bots.RegataSimulatorBot;
import com.boatarde.regatasimulator.factory.TelegramTestFactory;
import com.boatarde.regatasimulator.flows.WorkflowAction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Document;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.PhotoSize;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.generics.TelegramBot;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class SubmissionRoutesTest {

    private static final String HEADER = "Area,Source,TLx,TLy,TRx,TRy,BRx,BRy,BLx,BLy,Background";
    private static final String CSV = HEADER + "\n1,1,0,0,20,0,20,30,0,30,0";
    private static final String ITEM_ID = "12345678-1234-1234-1234-123456789abc";

    @Mock
    private RegataSimulatorBot bot;

    private final CreateSourceRoute sourceRoute = new CreateSourceRoute();
    private final CreateTemplateRoute templateRoute = new CreateTemplateRoute();

    @ParameterizedTest
    @CsvSource({"image/jpeg,source: barco", "image/jpeg,SoUrCe: barco",
        "image/png,source: barco", "image/png,SOURCE: barco"})
    void jpegAndPngDocumentsWithCaseInsensitiveSourceCaptionDispatchOnlySource(String mime, String caption) {
        Update update = document(mime, caption);

        assertEquals(Optional.of(WorkflowAction.CREATE_SOURCE), sourceRoute.test(update, bot));
        assertTrue(templateRoute.test(update, bot).isEmpty());
        verifyNoInteractions(bot);
    }

    @ParameterizedTest
    @ValueSource(strings = {"image/jpeg", "image/png"})
    void jpegAndPngDocumentsWithValidTemplateCsvDispatchOnlyTemplate(String mime) {
        Update update = document(mime, CSV);

        assertEquals(Optional.of(WorkflowAction.CREATE_TEMPLATE), templateRoute.test(update, bot));
        assertTrue(sourceRoute.test(update, bot).isEmpty());
        verifyNoInteractions(bot);
    }

    @ParameterizedTest
    @ValueSource(strings = {"source:", "source:   "})
    void emptySourceDescriptionStillDispatchesToStepForValidation(String caption) {
        assertEquals(Optional.of(WorkflowAction.CREATE_SOURCE), sourceRoute.test(document("image/png", caption), bot));
        verifyNoInteractions(bot);
    }

    @ParameterizedTest
    @ValueSource(strings = {"image/gif", "image/webp", "application/pdf", "IMAGE/PNG"})
    void unsupportedMimeTypesDoNotDispatchEitherSubmission(String mime) {
        assertTrue(sourceRoute.test(document(mime, "source: barco"), bot).isEmpty());
        assertTrue(templateRoute.test(document(mime, CSV), bot).isEmpty());
        verifyNoInteractions(bot);
    }

    @ParameterizedTest
    @NullAndEmptySource
    void missingCaptionDoesNotDispatchEitherSubmission(String caption) {
        Update update = document("image/png", caption);

        assertTrue(sourceRoute.test(update, bot).isEmpty());
        assertTrue(templateRoute.test(update, bot).isEmpty());
        verifyNoInteractions(bot);
    }

    @ParameterizedTest
    @ValueSource(strings = {"caption without submission prefix", " source: barco", "   "})
    void unrelatedOrLeadingWhitespaceCaptionDoesNotDispatch(String caption) {
        Update update = document("image/png", caption);

        assertTrue(sourceRoute.test(update, bot).isEmpty());
        assertTrue(templateRoute.test(update, bot).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {HEADER, HEADER + "\n1,1,0", "Wrong,Header\n1,1,0,0,20,0,20,30,0,30,0"})
    void emptyOrStructurallyInvalidTemplateCsvDoesNotDispatch(String caption) {
        assertTrue(templateRoute.test(document("image/png", caption), bot).isEmpty());
        verifyNoInteractions(bot);
    }

    @ParameterizedTest
    @CsvSource({"source,confirm,CONFIRM_REVIEW_SOURCE", "source,cancel,DELETE_REVIEW_SOURCE",
        "template,confirm,CONFIRM_REVIEW_TEMPLATE", "template,cancel,DELETE_REVIEW_TEMPLATE"})
    void confirmAndCancelCallbacksDispatchOnlyMatchingSubmissionRoute(String type, String action,
                                                                     WorkflowAction expected) {
        Update update = callback(ITEM_ID + ":" + type + ":" + action);
        if (type.equals("source")) {
            assertEquals(Optional.of(expected), sourceRoute.test(update, bot));
            assertTrue(templateRoute.test(update, bot).isEmpty());
        } else {
            assertEquals(Optional.of(expected), templateRoute.test(update, bot));
            assertTrue(sourceRoute.test(update, bot).isEmpty());
        }
        verifyNoInteractions(bot);
    }

    @ParameterizedTest
    @CsvSource({"source,confirm,CONFIRM_REVIEW_SOURCE", "source,cancel,DELETE_REVIEW_SOURCE",
        "template,confirm,CONFIRM_REVIEW_TEMPLATE", "template,cancel,DELETE_REVIEW_TEMPLATE"})
    void malformedCallbackUuidDispatchesExclusivelyToSafeRejectionHandler(String type, String action,
                                                                         WorkflowAction expected) {
        Update update = callback("not-a-uuid:" + type + ":" + action);

        assertEquals(Optional.of(expected), type.equals("source")
            ? sourceRoute.test(update, bot) : templateRoute.test(update, bot));
        verifyNoInteractions(bot);
    }

    @ParameterizedTest
    @ValueSource(strings = {"source:approve", "template:approve", "source:CONFIRM", "unknown:cancel"})
    void unrelatedOrCaseMismatchedCallbackDispatchesToExactlyOneRejectionHandler(String suffix) {
        Update update = callback(ITEM_ID + ":" + suffix);

        assertEquals(1, (sourceRoute.test(update, bot).isPresent() ? 1 : 0)
            + (templateRoute.test(update, bot).isPresent() ? 1 : 0));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"garbage", "1-1-1-1-1:source:cancel", ITEM_ID + ":template", ITEM_ID + ":template:cancel:extra"})
    void incompleteCallbackDataIsDispatchedOnceForAcknowledgement(String data) {
        Update update = callback(data);

        assertEquals(1, (sourceRoute.test(update, bot).isPresent() ? 1 : 0)
            + (templateRoute.test(update, bot).isPresent() ? 1 : 0));
        verifyNoInteractions(bot);
    }

    @ParameterizedTest
    @ValueSource(strings = {"source: barco", CSV})
    void photoUploadsAreIgnoredEvenWithSubmissionCaption(String caption) {
        Update update = document("image/png", caption);
        update.getMessage().setDocument(null);
        PhotoSize photo = new PhotoSize();
        photo.setFileId("photo-upload");
        update.getMessage().setPhoto(List.of(photo));

        assertTrue(update.getMessage().hasPhoto());
        assertTrue(sourceRoute.test(update, bot).isEmpty());
        assertTrue(templateRoute.test(update, bot).isEmpty());
        verifyNoInteractions(bot);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10})
    void currentlyMalformedCsvNumberEscapesTemplateRouteInsteadOfReturningEmpty(int fieldIndex) {
        // Phase1: normalize numeric parse failures into validation results rather than aborting routing.
        String[] fields = "1,1,0,0,20,0,20,30,0,30,0".split(",");
        fields[fieldIndex] = "not-a-number";
        Update update = document("image/png", HEADER + "\n" + String.join(",", fields));

        assertThrows(NumberFormatException.class, () -> templateRoute.test(update, bot));
        assertTrue(sourceRoute.test(update, bot).isEmpty());
        verifyNoInteractions(bot);
    }

    @Test
    void updatesWithoutMessageOrCallbackDoNotDispatch() {
        Update update = new Update();

        assertTrue(sourceRoute.test(update, bot).isEmpty());
        assertTrue(templateRoute.test(update, bot).isEmpty());
        verifyNoInteractions(bot);
    }

    @Test
    void textMessagesWithoutDocumentDoNotDispatch() {
        Update update = TelegramTestFactory.buildTextMessageUpdate("source: barco");

        assertTrue(sourceRoute.test(update, bot).isEmpty());
        assertTrue(templateRoute.test(update, bot).isEmpty());
        verifyNoInteractions(bot);
    }

    @Test
    void differentBotTypeDoesNotDispatchUploadsOrCallbacks() {
        TelegramBot otherBot = mock(TelegramBot.class);
        List<Update> updates = List.of(document("image/png", "source: barco"), document("image/jpeg", CSV),
            callback(ITEM_ID + ":source:confirm"), callback(ITEM_ID + ":template:cancel"));

        for (Update update : updates) {
            assertTrue(sourceRoute.test(update, otherBot).isEmpty());
            assertTrue(templateRoute.test(update, otherBot).isEmpty());
        }
        verifyNoInteractions(bot, otherBot);
    }

    private Update document(String mime, String caption) {
        Update update = TelegramTestFactory.buildTextMessageUpdate("upload");
        Message message = update.getMessage();
        message.setText(null);
        message.setCaption(caption);
        User user = new User();
        user.setId(42L);
        user.setFirstName("Ana");
        user.setIsBot(false);
        message.setFrom(user);
        Document document = new Document();
        document.setFileId("uploaded-file");
        document.setFileName("fixture.png");
        document.setMimeType(mime);
        message.setDocument(document);
        return update;
    }

    private Update callback(String data) {
        CallbackQuery query = new CallbackQuery();
        query.setId("callback-id");
        query.setData(data);
        Update update = new Update();
        update.setCallbackQuery(query);
        return update;
    }
}