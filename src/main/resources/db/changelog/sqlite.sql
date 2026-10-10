--liquibase formatted sql

--changeset regata:1 dbms:sqlite
CREATE TABLE sources (
    id TEXT PRIMARY KEY NOT NULL CHECK(length(id)=36),
    description TEXT,
    description_key TEXT UNIQUE,
    weight INTEGER NOT NULL CHECK(weight BETWEEN 1 AND 2147483647),
    status TEXT NOT NULL CHECK(status IN ('REVIEW','APPROVED','REJECTED')),
    message_json TEXT CHECK(message_json IS NULL OR json_valid(message_json)),
    author_id INTEGER, chat_id INTEGER, origin_date INTEGER,
    preview_chat_id INTEGER, preview_message_id INTEGER CHECK(preview_message_id IS NULL OR preview_message_id>0),
    CHECK((preview_chat_id IS NULL) = (preview_message_id IS NULL))
);
CREATE TABLE templates (
    id TEXT PRIMARY KEY NOT NULL CHECK(length(id)=36),
    areas_json TEXT NOT NULL CHECK(json_valid(areas_json) AND json_type(areas_json)='array' AND json_array_length(areas_json) BETWEEN 1 AND 128),
    weight INTEGER NOT NULL CHECK(weight BETWEEN 1 AND 2147483647),
    status TEXT NOT NULL CHECK(status IN ('REVIEW','APPROVED','REJECTED')),
    message_json TEXT CHECK(message_json IS NULL OR json_valid(message_json)),
    author_id INTEGER, chat_id INTEGER, origin_date INTEGER,
    preview_chat_id INTEGER, preview_message_id INTEGER CHECK(preview_message_id IS NULL OR preview_message_id>0),
    CHECK((preview_chat_id IS NULL) = (preview_message_id IS NULL))
);
CREATE TABLE users (id INTEGER PRIMARY KEY NOT NULL, first_name TEXT, last_name TEXT, user_name TEXT);
CREATE TABLE memes (
    id TEXT PRIMARY KEY NOT NULL CHECK(length(id)=36),
    template_uuid TEXT CHECK(template_uuid IS NULL OR length(template_uuid)=36),
    template_link TEXT REFERENCES templates(id) ON DELETE SET NULL,
    source_ids_present INTEGER NOT NULL CHECK(source_ids_present IN (0,1)),
    message_json TEXT CHECK(message_json IS NULL OR json_valid(message_json)),
    origin_date INTEGER
);
CREATE TABLE meme_sources (
    meme_id TEXT NOT NULL REFERENCES memes(id) ON DELETE CASCADE,
    position INTEGER NOT NULL CHECK(position>=0),
    source_uuid TEXT CHECK(source_uuid IS NULL OR length(source_uuid)=36),
    source_link TEXT REFERENCES sources(id) ON DELETE SET NULL,
    PRIMARY KEY(meme_id,position)
);

--changeset regata:2 dbms:sqlite
CREATE INDEX sources_gallery ON sources(status,origin_date DESC,id DESC);
CREATE INDEX sources_author ON sources(author_id,origin_date DESC,id DESC);
CREATE INDEX templates_gallery ON templates(status,origin_date DESC,id DESC);
CREATE INDEX templates_author ON templates(author_id,origin_date DESC,id DESC);
CREATE INDEX history_order ON memes(origin_date DESC,id DESC);
CREATE INDEX history_template_link ON memes(template_link);
CREATE INDEX history_source_link ON meme_sources(source_link);