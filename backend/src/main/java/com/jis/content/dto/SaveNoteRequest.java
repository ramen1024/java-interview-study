package com.jis.content.dto;

import jakarta.validation.constraints.Size;

public record SaveNoteRequest(

        @Size(max = 20_000, message = "笔记最长 20000 字符")
        String contentMd
) {
}
