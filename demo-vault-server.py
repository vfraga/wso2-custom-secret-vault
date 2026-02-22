"""
Demo REST API server that mimics a key vault (CyberArk, HashiCorp Vault, etc.).

This is a minimal Flask application used solely for local testing of the
custom-secret-handler WSO2 bundle. It stores secrets in a flat JSON file
and exposes them via a simple POST endpoint.

Usage:
    python3 -m venv .venv
    source .venv/bin/activate
    pip install -r requirements.txt
    python demo-vault-server.py

The server listens on http://0.0.0.0:5001/secrets by default.

Request:
    POST /secrets
    Content-Type: application/json
    { "alias": "<alias_name>" }

Response (200):
    Content-Type: application/json
    { "secret": "<plaintext_value>" }

Response (404):
    { "error": "Secret not found" }
"""

import json
import os

from flask import Flask, request, jsonify

app = Flask(__name__)

SECRETS_FILE = 'secrets.json'


def load_secrets():
    if not os.path.exists(SECRETS_FILE):
        return {}
    try:
        with open(SECRETS_FILE, 'r') as secrets_file:
            return json.load(secrets_file)
    except json.JSONDecodeError:
        return {}


@app.route('/secrets', methods=['POST'])
def get_secret():
    data = request.get_json()
    if not data or 'alias' not in data:
        return jsonify({'error': 'Missing alias in request body'}), 400

    alias = data['alias']
    secrets = load_secrets()

    if alias in secrets:
        return jsonify({'secret': secrets[alias]}), 200
    else:
        return jsonify({'error': 'Secret not found'}), 404


def main():
    # Create a dummy secrets file if it doesn't exist
    if not os.path.exists(SECRETS_FILE):
        with open(SECRETS_FILE, 'w') as f:
            json.dump({
                "DB_PASSWORD": "super_secret_password",
                "API_KEY": "12345-abcde-67890",
                "foo": "foo_val",
                "bar": "bar_val"
            }, f, indent=4)
        print(f"Created sample {SECRETS_FILE}")

    app.run(host='0.0.0.0', port=5001, debug=True)


if __name__ == '__main__':
    main()
