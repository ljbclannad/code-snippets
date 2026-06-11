# Knowledge Base Service Upgrade

## Goal

Upgrade `knowledge-base-service` from a basic upload-parse-embed-search flow into an operable knowledge base platform focused on:

- document governance
- structured metadata
- retrieval quality

This upgrade intentionally does not integrate with `ai-chat-service` yet. The service should first become stable and self-sufficient.

## Scope

The upgrade includes three major capability areas.

### 1. Document Governance

- semantic deduplication as the primary rule
- content hash deduplication as the strong secondary rule
- single current-state document model, no version chain
- archive and reactivate support
- effective-document only retrieval
- duplicate, archived, failed, and processing documents excluded from default retrieval

### 2. Structured Metadata

Required metadata on upload:

- title
- tags
- business domain
- source
- applicable scope
- trust level

Metadata strategy:

- required fields are provided by the uploader
- extended fields are extracted by DeepSeek
- extracted fields do not overwrite required user input

### 3. Retrieval Quality

- DeepSeek query rewrite
- hybrid recall: vector recall plus keyword recall
- DeepSeek rerank
- metadata-aware filtering
- result explanations

## Target Model

### Core Objects

- `KnowledgeBase`: knowledge base level configuration
- `KnowledgeDocument`: main document record and governance state
- `KnowledgeDocumentParseResult`: parse, extraction, and dedup result
- `KnowledgeDocumentChunk`: persisted chunk record for governance and rebuild

### Document State

Recommended states:

- `UPLOADED`
- `PROCESSING`
- `PARSE_FAILED`
- `SUSPECTED_DUPLICATE`
- `DEDUPLICATED`
- `ACTIVE`
- `ARCHIVED`
- `FAILED`

Only `ACTIVE` documents are returned by default search.

## Processing Flow

1. Upload file with required metadata.
2. Save document main record as `UPLOADED`.
3. Compute content hash and perform exact duplicate check.
4. Parse document content.
5. Extract extended metadata with DeepSeek.
6. Perform semantic dedup against effective documents in the same knowledge base.
7. Chunk document with upgraded chunking strategy.
8. Write chunk records to MySQL and vectors to Milvus.
9. Mark document `ACTIVE` only if all governance checks pass.

## Retrieval Flow

1. Normalize and rewrite query with DeepSeek.
2. Apply effective-document and metadata filters.
3. Run vector recall.
4. Run keyword recall over title, tags, summary, keywords, and chunk text.
5. Merge candidates.
6. Rerank with DeepSeek.
7. Return filtered results with metadata and hit reasons.

## Data Design

### `knowledge_document`

Main document table for:

- identity
- file info
- required metadata
- governance status
- dedup status
- archive status
- hash
- activation flag

### `knowledge_document_parse_result`

Parse and model extraction table for:

- summary
- keywords
- suggested tags
- extended metadata JSON
- dedup result JSON
- processing status and error detail

### `knowledge_document_chunk`

Chunk persistence table for:

- chunk content
- chunk index
- duplicated filter metadata
- activation and archive flags

Milvus remains the recall engine. MySQL remains the governance source of truth.

## API Direction

### Upload and Governance

- `POST /api/kb/{kbId}/documents`
- `GET /api/kb/{kbId}/documents/{docId}`
- `GET /api/kb/{kbId}/documents`
- `POST /api/kb/{kbId}/documents/{docId}/archive`
- `POST /api/kb/{kbId}/documents/{docId}/activate`
- `GET /api/kb/{kbId}/documents/{docId}/chunks`
- `GET /api/kb/{kbId}/documents/{docId}/parse-result`

### Search

- `POST /api/kb/{kbId}/search`

Search request should support:

- query
- tags
- businessDomain
- source
- applicableScope
- trustLevel
- titleKeyword
- useRewrite
- useRerank
- hybridSearch

## Recommended Build Order

1. schema and entity upgrade
2. upload API and metadata validation
3. governance status machine and dedup pipeline
4. parse result persistence
5. chunk persistence and rebuildability
6. hybrid search and metadata filters
7. DeepSeek rewrite and rerank
8. evaluation and observability

## Non-Goals

- no document history version chain
- no multi-tenant isolation in this phase
- no ai-chat integration in this phase

## Status

Design accepted. Implementation should start from schema and API contracts, not from search tweaks alone.
