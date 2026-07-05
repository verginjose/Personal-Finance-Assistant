-- Enable pgvector extension
CREATE EXTENSION IF NOT EXISTS vector;

-- Create table for financial AI rules
CREATE TABLE IF NOT EXISTS finance.financial_rules (
    id SERIAL PRIMARY KEY,
    rule_text TEXT NOT NULL,
    category VARCHAR(255),
    embedding vector(384), -- Using 384 dimensions (e.g. all-MiniLM-L6-v2 size)
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Create an index for vector similarity search (IVFFlat or HNSW)
-- Assuming we use pgvector's HNSW index for performance
CREATE INDEX ON finance.financial_rules USING hnsw (embedding vector_l2_ops);


