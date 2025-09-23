package com.example.personalchatbot.service.sql.druid.service;

import com.example.personalchatbot.service.sql.druid.implement.MongoDbFacadeImpl;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.bson.BsonValue;

import java.util.ArrayList;
import java.util.List;

public class MongoDbFacade implements MongoDbFacadeImpl {

    //--------------- Helpers-------------------
    private List<BsonDocument> normalizeToDocs(String input) {
        String s = input.trim();
        List<BsonDocument> docs = new ArrayList<>();
        if (s.startsWith("[")) {
            var arr = BsonArray.parse(s);
            for (var v : arr) docs.add(v.asDocument());
        } else if (s.startsWith("{")) {
            docs.add(BsonDocument.parse(s));
        } else {
            // NDJSON
            for (String line : s.split("\r?\n")) {
                line = line.trim();
                if (!line.isEmpty()) docs.add(BsonDocument.parse(line));
            }
        }
        return docs;
    }

}
