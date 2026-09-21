package com.shitan.ai;

import jakarta.validation.constraints.NotBlank;

/**
 * 运营人员登记远程知识文档时提交的 JSON。
 *
 * @param url 需要在后台下载的 HTTP(S) 地址
 */
public record RemoteDocumentRequest(
        @NotBlank(message = "远程文档地址不能为空") String url
) {
}
