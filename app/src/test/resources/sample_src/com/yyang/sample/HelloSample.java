package com.yyang.sample;

public class HelloSample {
    public static final String MAGIC = "JADX_CORE_MCP_SAMPLE_MAGIC";

    public String greet(String name) {
        return "hello-" + name + "-" + MAGIC;
    }

    public int add(int a, int b) {
        return a + b;
    }
}
