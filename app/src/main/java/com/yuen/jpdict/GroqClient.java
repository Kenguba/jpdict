package com.yuen.jpdict;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Shared lightweight Groq client for Activity and WindowManager lookup surfaces. */
final class GroqClient {
    private GroqClient() {}

    static String query(String q, String apiKey) throws Exception {
        URL url = new URL(BuildConfig.GROQ_BASE_URL + "/chat/completions");
        HttpURLConnection c = (HttpURLConnection)url.openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(60000);
        c.setReadTimeout(60000);
        c.setDoOutput(true);
        c.setRequestProperty("Authorization", "Bearer " + apiKey);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");

        String systemPrompt =
                "你是多语种 OCR 文本理解与词典助手。用户输入是原始 OCR 文字或手动查询。"
                + "请自行判断语言：日语、中文、韩语、英语或混合文本。"
                + "不要默认输入是日语，不要把中文、英语、韩语一律翻译成日语。"
                + "简洁地用中文给出语言判断和准确含义；单词给出读音（若合适）、词性、释义及简短例句，"
                + "句子则给出自然中文翻译和必要的语法说明。"
                + "对共享汉字、短词或无法确定的语言不要武断猜测，可说明歧义。"
                + "OCR 有明显错字时可以注明疑点，但不可擅自改变原文。"
                + "不要输出推理过程。";

        JSONObject body = new JSONObject();
        body.put("model", BuildConfig.GROQ_MODEL);
        body.put("temperature", 0.35);
        body.put("max_tokens", 1000);

        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system").put("content", systemPrompt));
        messages.put(new JSONObject().put("role", "user").put("content", q));
        body.put("messages", messages);

        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = c.getOutputStream()) {
            out.write(bytes);
        }

        int code = c.getResponseCode();
        InputStream stream =
                code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        String response = readAll(stream);
        c.disconnect();

        if (code < 200 || code >= 300) {
            throw new IOException(
                    "HTTP " + code + (response.isEmpty() ? "" : " · " + response));
        }

        JSONObject json = new JSONObject(response);
        JSONArray choices = json.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            throw new IOException("Groq 返回内容为空");
        }

        JSONObject message = choices.getJSONObject(0).optJSONObject("message");
        String answer = message == null
                ? ""
                : message.optString("content", "").trim();
        if (answer.isEmpty()) throw new IOException("Groq 返回内容为空");
        return answer;
    }

    private static String readAll(InputStream stream) throws IOException {
        if (stream == null) return "";
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder b = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                if (b.length() > 0) b.append('\n');
                b.append(line);
            }
            return b.toString();
        }
    }
}
