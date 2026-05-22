#!/bin/bash
set -e

# If using SQLite, ensure the parent directory and file exist
if [[ $OTP_PUSH_SERVER_DATABASE_URL == sqlite://* ]]; then
    DB_PATH="${OTP_PUSH_SERVER_DATABASE_URL#sqlite://}"

    # Handle the case where the URL might be sqlite: (relative) or sqlite:/// (absolute)
    # The prefix removal might leave a / for absolute paths.

    DB_DIR=$(dirname "$DB_PATH")

    if [ ! -d "$DB_DIR" ]; then
        echo "Creating missing database directory: $DB_DIR"
        mkdir -p "$DB_DIR"
    fi

    if [ ! -f "$DB_PATH" ]; then
        echo "Creating missing database file: $DB_PATH"
        touch "$DB_PATH"
    fi
fi

# Run migrations
echo "Running database migrations..."
/usr/local/bin/migration up --database-url "$OTP_PUSH_SERVER_DATABASE_URL"

# Start the server
echo "Starting OTP Push Server..."
exec /usr/local/bin/server -c /home/app/config.toml --database-url "$OTP_PUSH_SERVER_DATABASE_URL"
