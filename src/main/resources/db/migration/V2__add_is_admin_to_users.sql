-- Add is_admin column to gate debug endpoint access to admin accounts only
ALTER TABLE users ADD COLUMN is_admin BOOLEAN NOT NULL DEFAULT FALSE;