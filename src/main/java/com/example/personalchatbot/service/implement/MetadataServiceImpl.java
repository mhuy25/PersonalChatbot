package com.example.personalchatbot.service.implement;

import com.example.personalchatbot.dto.MetadataDto;

import java.util.Locale;
import java.util.Map;

public interface MetadataServiceImpl {
    MetadataDto infer(String question, Locale locale);

    default Map<String,String> toFilters(MetadataDto m) {
        var f = new java.util.HashMap<String,String>();
        if (m.getProject()!=null) f.put("project", m.getProject());
        if (m.getModule()!=null)  f.put("module",  m.getModule());
        if (m.getEnv()!=null)     f.put("env",     m.getEnv());
        if (m.getTitle()!=null)   f.put("title",   m.getTitle());
        if (m.getPath()!=null)    f.put("path",    m.getPath());
        return f;
    }
}
