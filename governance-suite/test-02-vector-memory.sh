#!/bin/bash

# =============================================
# Test Script: Vector Memory Service
# Description: Test memory extraction and retrieval
# Service: vector-memory-service (port 8102)
# =============================================

set -e

echo "=========================================="
echo "Test 2: Vector Memory Service"
echo "=========================================="
echo ""

# Configuration
CUSTOMER_ID="1001"
GROUP_ID="health-assessment-group"
CORRELATION_ID="test-memory-$(date +%s)"

# Test 2.1: Memory Extraction (via RabbitMQ)
echo "[Test 2.1] Memory Extraction"
echo "----------------------------------------"

# Create Python script for memory extraction
cat > /tmp/test_memory_extraction.py << 'PYTHON_SCRIPT'
import pika
import json
import sys
import time

def test_memory_extraction():
    """Test memory extraction via RabbitMQ"""

    # Configuration
    customer_id = sys.argv[1] if len(sys.argv) > 1 else "1001"
    group_id = sys.argv[2] if len(sys.argv) > 2 else "health-assessment-group"
    correlation_id = sys.argv[3] if len(sys.argv) > 3 else f"test-extraction-{int(time.time())}"

    print(f"Testing Memory Extraction")
    print(f"Customer ID: {customer_id}")
    print(f"Group ID: {group_id}")
    print(f"Correlation ID: {correlation_id}")
    print("-" * 50)

    try:
        # Connect to RabbitMQ
        connection = pika.BlockingConnection(
            pika.ConnectionParameters(host='localhost', port=5672)
        )
        channel = connection.channel()

        # Prepare extraction request
        request = {
            "correlationId": correlation_id,
            "groupId": group_id,
            "customerId": customer_id,
            "userMessage": "我最近血压有点高，大概在140/90左右，需要注意什么吗？",
            "aiResponse": "您的血压140/90mmHg属于1级高血压。建议：1) 减少盐分摄入，每日控制在5克以内；2) 增加运动，每周至少150分钟中等强度运动；3) 定期监测血压；4) 如持续偏高，建议就医。",
            "timestamp": int(time.time() * 1000)
        }

        print(f"\n[1] Sending extraction request to memory.extraction.exchange...")
        print(f"    User Message: {request['userMessage'][:50]}...")
        print(f"    AI Response: {request['aiResponse'][:50]}...")

        # Send extraction request (fire-and-forget, no response expected)
        channel.basic_publish(
            exchange='memory.extraction.exchange',
            routing_key='memory.extraction',
            body=json.dumps(request),
            properties=pika.BasicProperties(
                content_type='application/json',
                correlation_id=correlation_id
            )
        )

        print(f"[2] Extraction request sent successfully")
        print(f"[3] Memory extraction is asynchronous (no immediate response)")
        print(f"    The service will process this in the background")

        # Cleanup
        connection.close()

        print(f"\n✓ Memory extraction request submitted successfully")

    except Exception as e:
        print(f"\n✗ Test FAILED with exception: {e}")
        import traceback
        traceback.print_exc()
        sys.exit(1)

if __name__ == "__main__":
    test_memory_extraction()
PYTHON_SCRIPT

# Run memory extraction test
python3 /tmp/test_memory_extraction.py "$CUSTOMER_ID" "$GROUP_ID" "$CORRELATION_ID"

echo ""
echo "[Waiting 10 seconds for memory extraction to complete...]"
sleep 10

# Test 2.2: Memory Retrieval (via RabbitMQ)
echo ""
echo "[Test 2.2] Memory Retrieval"
echo "----------------------------------------"

# Create Python script for memory retrieval
cat > /tmp/test_memory_retrieval.py << 'PYTHON_SCRIPT'
import pika
import json
import sys
import time

def test_memory_retrieval():
    """Test memory retrieval via RabbitMQ"""

    # Configuration
    customer_id = sys.argv[1] if len(sys.argv) > 1 else "1001"
    group_id = sys.argv[2] if len(sys.argv) > 2 else "health-assessment-group"
    correlation_id = sys.argv[3] if len(sys.argv) > 3 else f"test-retrieval-{int(time.time())}"

    print(f"Testing Memory Retrieval")
    print(f"Customer ID: {customer_id}")
    print(f"Group ID: {group_id}")
    print(f"Correlation ID: {correlation_id}")
    print("-" * 50)

    try:
        # Connect to RabbitMQ
        connection = pika.BlockingConnection(
            pika.ConnectionParameters(host='localhost', port=5672)
        )
        channel = connection.channel()

        # Declare response queue (exclusive, auto-delete)
        response_queue_name = f"vector.memory.response.{correlation_id}"
        channel.queue_declare(queue=response_queue_name, exclusive=True, auto_delete=True)

        # Bind response queue to exchange
        channel.queue_bind(
            exchange='vector.memory.exchange',
            queue=response_queue_name,
            routing_key=f"vector.memory.response.{correlation_id}"
        )

        # Prepare retrieval request
        request = {
            "correlationId": correlation_id,
            "groupId": group_id,
            "customerId": customer_id,
            "query": "血压高怎么办",
            "topK": 5,
            "timestamp": int(time.time() * 1000)
        }

        print(f"\n[1] Sending retrieval request to vector.memory.exchange...")
        print(f"    Query: {request['query']}")
        print(f"    Top K: {request['topK']}")

        # Send retrieval request
        channel.basic_publish(
            exchange='vector.memory.exchange',
            routing_key='vector.memory.request',
            body=json.dumps(request),
            properties=pika.BasicProperties(
                content_type='application/json',
                correlation_id=correlation_id
            )
        )

        print(f"[2] Retrieval request sent successfully")
        print(f"[3] Waiting for response on queue: {response_queue_name}...")

        # Wait for response (timeout: 10 seconds)
        response_received = False

        def callback(ch, method, properties, body):
            nonlocal response_received
            response_received = True

            print(f"\n[4] Response received!")
            response = json.loads(body)

            # Validate response
            if response.get('success'):
                print(f"\n✓ Test PASSED")
                memories = response.get('data', [])
                print(f"  - Retrieved {len(memories)} memories")

                for i, memory in enumerate(memories, 1):
                    print(f"\n  Memory {i}:")
                    print(f"    - Type: {memory.get('memoryType')}")
                    print(f"    - Key: {memory.get('memoryKey')}")
                    print(f"    - Content: {memory.get('memoryContent')[:80]}...")
                    print(f"    - Similarity: {memory.get('similarityScore', 0):.4f}")
            else:
                print(f"\n✗ Test FAILED")
                print(f"  - Error: {response.get('errorMessage')}")

            ch.stop_consuming()

        # Consume response
        channel.basic_consume(
            queue=response_queue_name,
            on_message_callback=callback,
            auto_ack=True
        )

        # Start consuming with timeout
        connection.process_data_events(time_limit=10)

        if not response_received:
            print(f"\n✗ Test FAILED: No response received within 10 seconds")
            sys.exit(1)

        # Cleanup
        connection.close()

    except Exception as e:
        print(f"\n✗ Test FAILED with exception: {e}")
        import traceback
        traceback.print_exc()
        sys.exit(1)

if __name__ == "__main__":
    test_memory_retrieval()
PYTHON_SCRIPT

# Run memory retrieval test
RETRIEVAL_CORRELATION_ID="test-retrieval-$(date +%s)"
python3 /tmp/test_memory_retrieval.py "$CUSTOMER_ID" "$GROUP_ID" "$RETRIEVAL_CORRELATION_ID"

# Cleanup
rm -f /tmp/test_memory_extraction.py
rm -f /tmp/test_memory_retrieval.py

echo ""
echo "=========================================="
echo "Test 2 Complete"
echo "=========================================="
