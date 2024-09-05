package com.boatarde.regatasimulator.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.methods.send.SendMediaGroup;
import org.telegram.telegrambots.meta.api.objects.media.InputMedia;
import org.telegram.telegrambots.meta.api.objects.media.InputMediaPhoto;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@ExtendWith(MockitoExtension.class)
class TelegramUtilsTest {

    @Test
    void testNormalizeMediaGroupCaptionWhenDifferentCaption() {
        List<InputMedia> inputMedia = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            inputMedia.add(InputMediaPhoto.builder()
                .media(String.format("fileId%d", i))
                .caption(String.format("caption%d", i))
                .build());
        }
        SendMediaGroup sendMediaGroup = SendMediaGroup.builder()
            .chatId("1234")
            .medias(inputMedia)
            .build();
        TelegramUtils.normalizeMediaGroupCaption(sendMediaGroup);
        for (int i = 0; i < 10; i++) {
            assertEquals(String.format("caption%d", i), sendMediaGroup.getMedias().get(i).getCaption());
        }
    }

    @Test
    void testNormalizeMediaGroupCaptionWhenSameCaption() {
        List<InputMedia> inputMedia = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            inputMedia.add(InputMediaPhoto.builder()
                .media(String.format("fileId%d", i))
                .caption("same_caption")
                .build());
        }
        SendMediaGroup sendMediaGroup = SendMediaGroup.builder()
            .chatId("1234")
            .medias(inputMedia)
            .build();
        TelegramUtils.normalizeMediaGroupCaption(sendMediaGroup);
        assertEquals("same_caption", sendMediaGroup.getMedias().getFirst().getCaption());
        for (int i = 1; i < 10; i++) {
            assertNull(sendMediaGroup.getMedias().get(i).getCaption());
        }
    }
}