package com.shitan.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 用 JDK HTTP 客户端获取远程文档，并用主机白名单、超时和大小上限约束服务器主动访问。
 */
@Component
public class RemoteDocumentFetcher {

    private static final int MAX_DOCUMENT_BYTES = 1_000_000;

    private final HttpClient httpClient;
    private final Set<String> allowedHosts;

    /**
     * 从配置读取逗号分隔的允许主机；默认空集合表示生产启动后不主动访问任何远程地址。
     *
     * @param allowedHostsText 例如 docs.example.com,localhost
     */
    public RemoteDocumentFetcher(
            @Value("${ragent.remote-allowed-hosts:}") String allowedHostsText
    ) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.allowedHosts = Arrays.stream(allowedHostsText.split(","))
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .map(String::toLowerCase)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * 校验协议和主机后发送 GET，只接收 2xx 且不超过 1 MB 的原始字节。
     *
     * @param sourceUri 运营人员提交的远程文档地址
     * @return 可以原样落盘并交给解析器的文档字节
     * @throws IOException          地址被拒绝、状态码错误或正文过大
     * @throws InterruptedException 等待响应时后台线程被中断
     */
    public byte[] fetch(URI sourceUri) throws IOException, InterruptedException {
        validateAddress(sourceUri);
        HttpRequest request = HttpRequest.newBuilder(sourceUri)
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        HttpResponse<byte[]> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofByteArray()
        );
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("远程文档返回 HTTP " + response.statusCode());
        }
        if (response.body().length > MAX_DOCUMENT_BYTES) {
            throw new IOException("远程文档超过 1 MB 上限");
        }
        return response.body();
    }

    /**
     * 当前只允许 HTTP(S) 且主机必须显式列入白名单，降低任意 URL 带来的 SSRF 风险。
     *
     * @param sourceUri 待访问地址
     */
    private void validateAddress(URI sourceUri) {
        String scheme = sourceUri.getScheme();
        String host = sourceUri.getHost();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("远程文档只支持 http 或 https 地址");
        }
        if (host == null || !allowedHosts.contains(host.toLowerCase())) {
            throw new IllegalArgumentException("远程主机未加入白名单：" + host);
        }
    }
}
