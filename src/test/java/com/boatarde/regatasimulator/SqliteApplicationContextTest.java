package com.boatarde.regatasimulator;

import com.boatarde.regatasimulator.application.*;
import com.boatarde.regatasimulator.bots.RegataSimulatorBot;
import com.boatarde.regatasimulator.configuration.TelegramBotRegistration;
import com.boatarde.regatasimulator.factory.ImageTestFactory;
import com.boatarde.regatasimulator.models.*;
import com.boatarde.regatasimulator.repository.*;
import com.boatarde.regatasimulator.repository.sqlite.SqliteStore;
import com.boatarde.regatasimulator.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"regata-simulator.database.engine=sqlite", "telegram.bots.regata-simulator.registration-enabled=false", "regata-simulator.scheduling.enabled=false"})
@ActiveProfiles("test")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class SqliteApplicationContextTest {
    @TempDir static Path temp;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("regata-simulator.database.sqlite-file",() -> temp.resolve("candidate.db").toString());
        r.add("regata-simulator.database.path",() -> temp.resolve("unused-json").toString());
        r.add("regata-simulator.sources.path",() -> temp.resolve("sources").toString());
        r.add("regata-simulator.templates.path",() -> temp.resolve("templates").toString());
    }
    @MockBean RegataSimulatorBot bot;
    @MockBean TelegramBotRegistration registration;
    @MockBean TelegramGateway telegram;
    @MockBean ImageRenderer renderer;
    @Autowired ApplicationContext context;
    @Autowired SqliteStore store;
    @Autowired SourceRepository sources;
    @Autowired TemplateRepository templates;
    @Autowired MemeHistoryRepository history;
    @Autowired MemeService memes;
    @Autowired MetadataUnitOfWork metadata;
    @Test void optsInWithoutJsonDbAndRollsBackPublicationMetadataAfterExternalDelivery() throws Exception {
        assertTrue(context.getBeansOfType(io.jsondb.JsonDBTemplate.class).isEmpty());
        assertFalse(Files.exists(temp.resolve("unused-json")));
        assertFalse(context.containsBean("org.springframework.context.annotation.internalScheduledAnnotationProcessor"));
        Source source=new Source(); source.setId(UUID.randomUUID()); source.setDescription("synthetic"); source.setWeight(10); source.setStatus(Status.APPROVED); sources.insertSubmission(source);
        Template template=new Template(); template.setId(UUID.randomUUID()); template.setWeight(10); template.setStatus(Status.APPROVED);
        template.setAreas(List.of(TemplateArea.builder().index(1).source(1).topLeft(new AreaCorner(0,0)).topRight(new AreaCorner(20,0)).bottomRight(new AreaCorner(20,20)).bottomLeft(new AreaCorner(0,20)).build())); templates.insertSubmission(template);
        ImageTestFactory.image(Files.createDirectories(temp.resolve("sources").resolve(source.getId().toString())).resolve("source.png"));
        ImageTestFactory.image(Files.createDirectories(temp.resolve("templates").resolve(template.getId().toString())).resolve("template.png"));
        when(renderer.render(any())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            Path job=Files.createTempDirectory(temp,"render-"); return new ImageRenderer.RenderedImage(ImageTestFactory.image(job.resolve("final.png")),job);
        });
        when(telegram.sendPhoto(any())).thenAnswer(call -> { assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); return new TelegramGateway.Delivery(123,321,null); });
        store.jdbc().execute("CREATE TRIGGER fail_history BEFORE INSERT ON memes BEGIN SELECT RAISE(ABORT,'synthetic'); END");
        var request=new MemeService.Publish(MemeService.Origin.ADMIN,TelegramGateway.Destination.chat(123));
        assertThrows(ApplicationFailure.class,() -> memes.publish(request));
        assertEquals(10,sources.findById(source.getId()).orElseThrow().getWeight()); assertEquals(10,templates.findById(template.getId()).orElseThrow().getWeight()); assertTrue(history.newestFirst().isEmpty());
        store.jdbc().execute("DROP TRIGGER fail_history"); memes.publish(request);
        assertEquals(9,sources.findById(source.getId()).orElseThrow().getWeight()); assertEquals(9,templates.findById(template.getId()).orElseThrow().getWeight()); assertEquals(1,history.newestFirst().size());
        assertThrows(IllegalStateException.class,() -> metadata.execute(() -> { sources.decreaseWeight(source.getId()); throw new IllegalStateException("synthetic rollback"); }));
        assertEquals(9,sources.findById(source.getId()).orElseThrow().getWeight()); verifyNoInteractions(bot,registration);
    }
}