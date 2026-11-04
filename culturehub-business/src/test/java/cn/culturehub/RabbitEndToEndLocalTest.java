package cn.culturehub;

@org.junit.jupiter.api.condition.EnabledIfSystemProperty(
    named = "culturehub.rabbitLocalTest",
    matches = "true")
class RabbitEndToEndLocalTest extends RabbitEndToEndTest {}
