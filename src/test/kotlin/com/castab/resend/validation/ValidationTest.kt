package com.castab.resend.validation

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json

private val json = Json

private fun create(body: String) = validateCreateBody(json.parseToJsonElement(body))
private fun message(body: String) = validateMessageBody(json.parseToJsonElement(body))

class ValidationTest : StringSpec({
    "create requires topic and participant objects" {
        (create("""{}""") as Invalid).error shouldBe "topic and participant objects are required"
    }
    "create rejects an invalid topic type" {
        val body = """{"topic":{"type":"Bad Type","externalId":"x","title":"T"},"participant":{"email":"a@b.co"},"message":{"text":"hi"}}"""
        (create(body) as Invalid).error shouldBe "topic type, externalId, and title are invalid"
    }
    "create rejects an invalid participant email" {
        val body = """{"topic":{"type":"order","externalId":"x","title":"T"},"participant":{"email":"nope"},"message":{"text":"hi"}}"""
        (create(body) as Invalid).error shouldBe "participant.email must be a valid email address"
    }
    "create requires text or html" {
        val body = """{"topic":{"type":"order","externalId":"x","title":"T"},"participant":{"email":"a@b.co"},"message":{}}"""
        (create(body) as Invalid).error shouldBe "message.text or message.html is required"
    }
    "create rejects a reply-to name with angle brackets" {
        val body = """{"topic":{"type":"order","externalId":"x","title":"T"},"participant":{"email":"a@b.co"},"message":{"text":"hi","replyToName":"<x>"}}"""
        (create(body) as Invalid).error shouldBe "message.replyToName must be a header-safe string of at most 256 characters"
    }
    "create normalizes a valid body" {
        val body = """{"topic":{"type":"order_1","externalId":"ext-1","title":"  Hello  "},"participant":{"email":"a@b.co","name":" Ada "},"subject":" Subj ","message":{"text":"hi"}}"""
        val result = (create(body) as Valid).value
        result.topic.title shouldBe "Hello"
        result.participant.name shouldBe "Ada"
        result.subject shouldBe "Subj"
        result.message.text shouldBe "hi"
        result.message.html shouldBe null
    }
    "message requires text or html" {
        (message("""{}""") as Invalid).error shouldBe "text or html is required"
    }
    "message rejects a non-uuid replyToMessageId" {
        (message("""{"text":"hi","replyToMessageId":"nope"}""") as Invalid).error shouldBe "replyToMessageId must be a UUID"
    }
    "message accepts a valid body" {
        val result = message("""{"html":"<p>hi</p>","replyToMessageId":"0190bd3e-1c4a-7000-8000-000000000000"}""")
        result.shouldBeInstanceOf<Valid<MessageBodyInput>>()
    }
    "topic assignment validator enforces the topic shape" {
        (validateTopic(json.parseToJsonElement("""{"topic":{"type":"order","externalId":"x","title":"T"}}""")) as Valid).value.type shouldBe "order"
        (validateTopic(json.parseToJsonElement("""{}""")) as Invalid).error shouldBe "topic is required"
    }
})
