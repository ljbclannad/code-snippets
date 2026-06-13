# Knowledge Base Service

`knowledge-base-service` is a standalone Spring Boot service for governed document ingestion and semantic retrieval.

It currently supports:

- required structured metadata on upload
- exact duplicate detection by content hash
- semantic duplicate detection with DeepSeek
- DeepSeek metadata extraction
- chunk persistence in MySQL
- vector persistence in Milvus
- hybrid retrieval with vector recall plus keyword recall
- DeepSeek query rewrite
- DeepSeek rerank
- document governance operations such as archive, activate, reprocess
- recoverable processing with stage, progress, retry count, and last error stage

This service is not integrated into `ai-chat-service` yet.

## Port

- `8103`

## Dependencies

The service expects these local dependencies:

- MySQL: `localhost:3306`
- Redis: `localhost:36379`
- Nacos: `localhost:8848`
- Milvus: `localhost:19530`
- DashScope embedding key: `DASHSCOPE_API_KEY`
- DeepSeek key: `DEEPSEEK_API_KEY`

## Data Model

Core tables:

- `knowledge_base`
- `knowledge_document`
- `knowledge_document_parse_result`
- `knowledge_document_chunk`

MySQL is the source of truth for document state and chunk metadata. Milvus is used for vector recall.

## Document Governance

Required upload metadata:

- `title`
- `tags`
- `businessDomain`
- `source`
- `applicableScope`
- `trustLevel`

Main document statuses:

- `UPLOADED`
- `PROCESSING`
- `PARSE_FAILED`
- `DEDUPLICATED`
- `SUSPECTED_DUPLICATE`
- `ACTIVE`
- `ARCHIVED`
- `FAILED`

Only `ACTIVE` documents are returned by default search.

## Processing Stages

Recoverable processing fields are tracked on each document:

- `processingStage`
- `processingProgress`
- `retryCount`
- `lastRetryTime`
- `lastErrorStage`

Current stage values:

- `UPLOADED`
- `HASH_DEDUP`
- `PARSING`
- `METADATA_EXTRACTION`
- `SEMANTIC_DEDUP`
- `CHUNK_PERSIST`
- `VECTOR_INDEXING`
- `COMPLETED`

## Retrieval

Search supports:

- metadata filtering
- keyword recall from chunk records
- vector recall from Milvus
- hybrid merge
- optional DeepSeek rewrite
- optional DeepSeek rerank

Search request fields include:

- `query`
- `topK`
- `similarityThreshold`
- `rerank`
- `hybridSearch`
- `useRewrite`
- `titleKeyword`
- `tags`
- `businessDomain`
- `source`
- `applicableScope`
- `trustLevel`
- `docIds`

## API

### Knowledge Base

- `POST /api/kb`
- `GET /api/kb/{kbId}`
- `DELETE /api/kb/{kbId}`

### Documents

- `POST /api/kb/{kbId}/documents`
- `GET /api/kb/{kbId}/documents`
- `GET /api/kb/{kbId}/documents/{docId}`
- `GET /api/kb/{kbId}/documents/{docId}/parse-result`
- `GET /api/kb/{kbId}/documents/{docId}/chunks`
- `POST /api/kb/{kbId}/documents/{docId}/archive`
- `POST /api/kb/{kbId}/documents/{docId}/activate`
- `POST /api/kb/{kbId}/documents/{docId}/reprocess`
- `DELETE /api/kb/{kbId}/documents/{docId}`

### Search

- `POST /api/kb/{kbId}/search`

## Upload Example

```bash
curl -X POST "http://localhost:8103/api/kb/<kbId>/documents" \
  -F "file=@test_document.txt" \
  -F "title=高血压治疗指南" \
  -F "tags=高血压,慢病,治疗" \
  -F "businessDomain=medical" \
  -F "source=manual_upload" \
  -F "applicableScope=doctor_assistant" \
  -F "trustLevel=HIGH" \
  -F "createdBy=test_user"
```

## Search Example

```bash
curl -X POST "http://localhost:8103/api/kb/<kbId>/search" \
  -H "Content-Type: application/json" \
  -d '{
    "query": "高血压怎么治疗",
    "topK": 5,
    "similarityThreshold": 0.1,
    "hybridSearch": true,
    "useRewrite": true,
    "rerank": true,
    "businessDomain": "medical",
    "trustLevel": "HIGH"
  }'
```

## Reprocess Example

```bash
curl -X POST "http://localhost:8103/api/kb/<kbId>/documents/<docId>/reprocess"
```

Use this for documents in non-active states such as:

- `FAILED`
- `PARSE_FAILED`
- `SUSPECTED_DUPLICATE`
- `DEDUPLICATED`

It is rejected for:

- `PROCESSING`
- `ACTIVE`
- `ARCHIVED`

## Local Run

```bash
mvn -q -pl ai/knowledge-base-service spring-boot:run
```

## Verification

Module tests:

```bash
mvn -pl ai/knowledge-base-service test
```

Smoke test:

```bash
bash sh/test/test_knowledge_base_service.sh
```

## Notes

- Database schema in the current local setup was patched directly in Docker MySQL rather than maintained via a migration script.
- `ai/knowledge-base-service/src/main/resources/db/init_kb_service.sql` reflects the intended current schema for fresh initialization.
