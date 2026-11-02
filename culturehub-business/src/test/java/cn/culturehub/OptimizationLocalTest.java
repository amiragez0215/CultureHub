package cn.culturehub;
@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="culturehub.localTest",matches="true")
class OptimizationLocalTest extends OptimizationIntegrationTest {}
