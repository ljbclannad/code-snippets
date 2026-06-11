# Knowledge Base Capability Upgrade Design

## Overview

This design upgrades `knowledge-base-service` from a basic ingestion and vector search service into a knowledge base platform with:

- document governance
- structured metadata
- retrieval quality controls

The goal is to make the service operationally usable on its own before any downstream integration such as `ai-chat-service`.

## Objectives

- make document ingestion governance-aware instead of upload-only
- enforce structured metadata at ingestion time
- improve retrieval quality with rewrite, hybrid recall, rerank, and filtering
- keep MySQL as the governance source of truth and Milvus as the recall engine
- default all retrieval to effective documents only

## Explicit Scope

This phase includes:

- semantic deduplication as the primary dedup strategy
- content hash deduplication as secondary strong validation
- required metadata plus DeepSeek-extracted extended metadata
- archive and reactivate operations
- chunk persistence in MySQL
- query rewrite with DeepSeek
- hybrid recall using vector recall plus keyword recall
- rerank with DeepSeek
- metadata-aware filtering
- search result explanations

This phase does not include:

- document version history chains
- multi-tenant isolation
- ai-chat-service integration

## Functional Decisions

### Document Governance

- Documents are modeled as single current-state records.
- There is no version chain such as `v1`, `v2`, `v3`.
- Exact duplicate handling is based on content hash.
- Near-duplicate handling is based on semantic similarity.
- Duplicate or suspicious documents do not enter the effective search set.
- Archived documents do not enter the effective search set.
- Failed or processing documents do not enter the effective search set.

### Structured Metadata

Required metadata fields:

- title
- tags
- business domain
- source
- applicable scope
- trust level

Metadata capture strategy:

- required fields are supplied by the user at upload time
- extended metadata is extracted by DeepSeek
- model output augments metadata but does not override required user input

### Retrieval Rules

- default retrieval returns effective documents only
- filters apply before final result set construction
- vector retrieval alone is insufficient
- hybrid recall is required
- rerank is enabled through DeepSeek for final ordering

## Architecture

The design introduces four logical layers.

### 1. Governance Layer

Responsible for:

- document identity
- metadata validation
- state transitions
- archive/reactivate
- dedup decisions

Primary table:

- `knowledge_document`

### 2. Parsing and Enrichment Layer

Responsible for:

- file parsing
- summary extraction
- keyword extraction
- suggested tags
- semantic dedup evidence
- error recording

Primary table:

- `knowledge_document_parse_result`

### 3. Chunk Layer

Responsible for:

- stable persisted chunk records
- chunk-level metadata projection
- rebuild source for vector indexes

Primary table:

- `knowledge_document_chunk`

### 4. Vector Retrieval Layer

Responsible for:

- vector embedding
- Milvus write and delete
- vector recall

Milvus remains storage for embeddings and approximate recall only. It is not the governance source of truth.

## Core Domain Model

### KnowledgeBase

Existing role remains:

- knowledge base identity
- chunking and retrieval defaults
- Milvus collection binding

Minimal change:

- keep current responsibilities
- avoid putting governance-heavy document fields on the knowledge base record

### KnowledgeDocument

This becomes the main document record and must contain:

- document identity
- file info
- required metadata
- content hash
- governance status
- duplicate status
- archive flag
- activation flag
- processing timestamps

Recommended conceptual fields:

- `docId`
- `kbId`
- `title`
- `tags`
- `businessDomain`
- `source`
- `applicableScope`
- `trustLevel`
- `fileName`
- `fileType`
- `fileSize`
- `filePath`
- `contentHash`
- `status`
- `isActive`
- `isArchived`
- `duplicateStatus`
- `duplicateTargetDocId`
- `duplicateScore`
- `chunkCount`
- `createdBy`
- `createTime`
- `updateTime`

### KnowledgeDocumentParseResult

This records one normalized processing result for a document and must contain:

- processing outcome
- summary
- keywords
- suggested tags
- extended metadata JSON
- dedup evidence
- processing error detail

Recommended conceptual fields:

- `docId`
- `parseStatus`
- `summary`
- `keywords`
- `suggestedTags`
- `extendedMetadata`
- `semanticDedupResult`
- `parseErrorMessage`
- `processedAt`

### KnowledgeDocumentChunk

This is a persistent chunk record in MySQL and must contain:

- chunk identity
- chunk content
- filtering metadata copied from the document
- activation state

Recommended conceptual fields:

- `chunkId`
- `docId`
- `kbId`
- `chunkIndex`
- `content`
- `title`
- `tags`
- `businessDomain`
- `source`
- `applicableScope`
- `trustLevel`
- `isActive`
- `isArchived`
- `duplicateStatus`

## Document State Machine

Recommended states:

- `UPLOADED`
- `PROCESSING`
- `PARSE_FAILED`
- `SUSPECTED_DUPLICATE`
- `DEDUPLICATED`
- `ACTIVE`
- `ARCHIVED`
- `FAILED`

State meanings:

- `UPLOADED`: file and required metadata accepted, processing not yet started
- `PROCESSING`: parse, enrichment, dedup, chunking, or embedding in progress
- `PARSE_FAILED`: parser or structured extraction failed before successful chunk persistence
- `SUSPECTED_DUPLICATE`: semantic similarity threshold matched an existing effective document
- `DEDUPLICATED`: exact hash duplicate found
- `ACTIVE`: fully processed, valid, searchable
- `ARCHIVED`: retained but excluded from search
- `FAILED`: processing completed unsuccessfully after retries or downstream write failure

Only `ACTIVE` is effective for default search.

## Ingestion Flow

### Step 1. Upload Acceptance

Input:

- file
- six required metadata fields

Behavior:

- validate required metadata
- validate file type and size
- save file
- create `knowledge_document` with status `UPLOADED`

### Step 2. Content Hash Dedup

Behavior:

- compute file content hash
- search for same hash within the same knowledge base among effective documents

Outcomes:

- if exact match exists, mark new record `DEDUPLICATED`
- store duplicate target document id
- stop before parse and embedding

### Step 3. Parse Content

Behavior:

- run parser by file type
- normalize parsed content into document paragraphs

Outcomes:

- if parse fails, mark `PARSE_FAILED`
- persist failure reason

### Step 4. Extract Extended Metadata

Behavior:

- call DeepSeek to extract summary, keywords, suggested tags, extended metadata, and quality hints

Rules:

- required user fields remain authoritative
- extracted fields are stored in `knowledge_document_parse_result`

### Step 5. Semantic Dedup

Behavior:

- compare current document against existing effective documents in the same knowledge base
- use title, summary, and representative content as dedup signal

Outcomes:

- if similarity exceeds threshold, mark `SUSPECTED_DUPLICATE`
- store matched document id and score
- do not activate for search

### Step 6. Chunking and Persistence

Behavior:

- build chunks with stable chunk ids
- persist chunk records in MySQL
- project filter fields into chunk rows

### Step 7. Embedding and Milvus Write

Behavior:

- write chunk embeddings to Milvus

Outcomes:

- if vector write fails, mark `FAILED`
- if vector write succeeds and all governance checks passed, mark `ACTIVE`

## Retrieval Flow

### Step 1. Query Rewrite

Behavior:

- send query to DeepSeek
- produce normalized query and expansion terms

Purpose:

- improve recall for colloquial queries
- expand domain terms and synonyms

### Step 2. Effective-Document Filtering

Behavior:

- restrict candidates to chunks from documents that are:
  - active
  - not archived
  - not deduplicated
  - not suspected duplicates

Additional filter support:

- tags
- business domain
- source
- applicable scope
- trust level
- title keyword
- created time range

### Step 3. Hybrid Recall

Behavior:

- vector recall from Milvus
- keyword recall from MySQL-backed indexed fields or existing text search layer

Keyword recall fields:

- title
- tags
- summary
- keywords
- chunk text

Purpose:

- compensate for weak vector behavior on exact terms, abbreviations, and domain phrases

### Step 4. Candidate Merge

Behavior:

- merge vector and keyword candidate sets by chunk id
- preserve provenance of hit reason

### Step 5. Rerank

Behavior:

- call DeepSeek with:
  - user query
  - normalized query
  - chunk content
  - title
  - tags
  - business domain
  - source
  - trust level

Outcome:

- final ranking score

### Step 6. Response Explanation

Return fields should include:

- chunk id
- doc id
- content
- final score
- hit reason
- title
- tags
- business domain
- source
- trust level

## API Design Direction

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

Recommended request additions:

- `query`
- `tags`
- `businessDomain`
- `source`
- `applicableScope`
- `trustLevel`
- `titleKeyword`
- `createdTimeFrom`
- `createdTimeTo`
- `useRewrite`
- `useRerank`
- `hybridSearch`

## Data Persistence Strategy

### knowledge_document

Use for:

- canonical document identity
- required metadata
- governance state
- dedup fields
- archive fields

### knowledge_document_parse_result

Use for:

- model-derived fields
- processing audit outcome
- semantic dedup evidence

### knowledge_document_chunk

Use for:

- stable chunk persistence
- filter projection
- index rebuild source

### Milvus

Use for:

- vector embeddings
- recall operations

Milvus is not used as the only source for document governance or active-state truth.

## Error Handling

- any parse failure must result in explicit document status update
- any dedup hit must be persisted, not only logged
- any vector write failure must mark the document non-effective
- archive and reactivate must synchronize MySQL and Milvus visibility state

## Observability

Recommended metrics:

- upload count
- parse success rate
- exact dedup hit rate
- semantic dedup hit rate
- active document count
- chunk count
- vector write latency
- rewrite latency
- rerank latency
- search latency by stage

Recommended logs:

- per document processing state transition
- dedup decisions with matched doc id and score
- vector write counts
- search stage timings

## Testing Strategy

### Unit Tests

- metadata validation
- exact dedup decision
- semantic dedup threshold logic
- state transitions
- chunk metadata projection
- search filter construction

### Integration Tests

- upload with required metadata
- exact duplicate upload
- semantic duplicate upload
- archive and reactivate
- hybrid recall with metadata filters
- rewrite plus rerank search path

### Evaluation Set

Introduce a fixed evaluation set later for:

- recall quality
- ranking quality
- latency
- embedding cost

## Implementation Order

1. schema migration
2. entity and mapper upgrade
3. upload API contract upgrade
4. document governance state machine
5. parse result persistence
6. chunk persistence in MySQL
7. exact dedup pipeline
8. semantic dedup pipeline
9. retrieval filter upgrade
10. hybrid recall implementation
11. DeepSeek query rewrite
12. DeepSeek rerank
13. metrics and operational cleanup

## Risks

- semantic dedup thresholds may be domain-sensitive and require tuning
- rerank can increase latency significantly if candidate sets are too large
- chunk metadata projection must stay in sync with document governance state
- hybrid recall requires careful candidate merging to avoid noisy results

## Recommendation

Implement governance and metadata foundations before retrieval polish. Retrieval quality depends on clean effective-document boundaries and stable metadata.
