# Server

## Get Docker up and running
- Rename the .env.example file into .env
- Fill out the .env file
- Execute the compose.yaml file
- Connect to pgAdmin: http://localhost:5050/
- Username for pgAdmin: admin@admin.com
- Password for pgAdmin: admin 
- Register Database:
  - General Tab:
    - Give it a name: e.g. Machi Koro DB 
  - Connection Tab:
    - Host name: postgres
    - Port: 5432
    - Maintenance Database: DB_NAME from .env file
    - Username: DB_USERNAME from .env file
    - PASSWORD: DB_PASSWORD from .env file