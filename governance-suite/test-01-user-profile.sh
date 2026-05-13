#!/bin/bash

# =============================================
# Test Script: User Profile Service
# Description: Test user profile retrieval via RabbitMQ
# Service: user-profile-service (port 8101)
# =============================================

set -e

echo "=========================================="
echo "Test 1: User Profile Service"
echo "=========================================="
echo ""

# Configuration
CUSTOMER_ID="1001"
CORRELATION_ID="test-profile-$(date +%s)"

# Create Python test script
cat > /tmp/test_user_profile.py << 'PYTHON_SCRIPT'
import pika
import json
import sys
import time
import uuid

def test_user_profile():
    """Test user profile service via RabbitMQ"""

    # Configuration
    customer_id = sys.argv[1] if len(sys.argv) > 1 else "1001"
    correlation_id = sys.argv[2] if len(sys.argv) > 2 else f"test-profile-{int(time.time())}"

    print(f"Testing User Profile Service")
    print(f"Customer ID: {customer_id}")
    print(f"Correlation ID: {correlation_id}")
    print("-" * 50)

    try:
        # Connect to RabbitMQ
        connection = pika.BlockingConnection(
            pika.ConnectionParameters(host='localhost', port=5672)
        )
        channel = connection.channel()

        # Declare response queue (exclusive, auto-delete)
        response_queue_name = f"user.profile.response.{correlation_id}"
        channel.queue_declare(queue=response_queue_name, exclusive=True, auto_delete=True)

        # Bind response queue to exchange
        channel.queue_bind(
            exchange='user.profile.exchange',
            queue=response_queue_name,
            routing_key=f"user.profile.response.{correlation_id}"
        )

        # Prepare request message
        request = {
            "correlationId": correlation_id,
            "customerId": customer_id,
            "timestamp": int(time.time() * 1000)
        }

        print(f"\n[1] Sending request to user.profile.exchange...")
        print(f"    Request: {json.dumps(request, indent=2)}")

        # Send request
        channel.basic_publish(
            exchange='user.profile.exchange',
            routing_key='user.profile.request',
            body=json.dumps(request),
            properties=pika.BasicProperties(
                content_type='application/json',
                correlation_id=correlation_id
            )
        )

        print(f"[2] Request sent successfully")
        print(f"[3] Waiting for response on queue: {response_queue_name}...")

        # Wait for response (timeout: 10 seconds)
        response_received = False

        def callback(ch, method, properties, body):
            nonlocal response_received
            response_received = True

            print(f"\n[4] Response received!")
            response = json.loads(body)
            print(f"    Response: {json.dumps(response, indent=2)}")

            # Validate response
            if response.get('success'):
                print(f"\n✓ Test PASSED")
                data = response.get('data', {})
                print(f"  - Customer ID: {data.get('customerId')}")
                print(f"  - Username: {data.get('username')}")
                print(f"  - Email: {data.get('email')}")
                print(f"  - Phone: {data.get('phone')}")
                print(f"  - Payment History Count: {len(data.get('paymentHistory', []))}")
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
    test_user_profile()
PYTHON_SCRIPT

# Check if Python and pika are available
echo "[Checking dependencies...]"
if ! command -v python3 &> /dev/null; then
    echo "✗ Python3 is not installed"
    exit 1
fi

if ! python3 -c "import pika" 2>/dev/null; then
    echo "✗ pika library is not installed"
    echo "  Install with: pip3 install pika"
    exit 1
fi

echo "✓ Dependencies OK"
echo ""

# Run the test
python3 /tmp/test_user_profile.py "$CUSTOMER_ID" "$CORRELATION_ID"

# Cleanup
rm -f /tmp/test_user_profile.py

echo ""
echo "=========================================="
echo "Test 1 Complete"
echo "=========================================="
