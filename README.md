# 🎓 Faculty Sync Tracker
*(Formerly College Faculty Attendance & Management System)*

A full-stack web application similar to **Keka** for managing college faculty attendance, leave requests, and employee profiles.

## Tech Stack

| Layer | Technology |
|-------|-----------|
| **Frontend** | React.js + Vite, Tailwind CSS, Recharts |
| **Backend** | Java 17, Spring Boot 3.2, Spring Security, JWT |
| **Database** | MySQL 8.0 |
| **Email** | Spring Mail (SMTP), Thymeleaf templates |

## Service Architecture & Ports

| Component | Default Port / URL | Configuration Key / Env Var |
| :--- | :--- | :--- |
| **Frontend SPA** | `http://localhost:3000` | `VITE_API_BASE_URL` (proxies `/api` to 8081) |
| **Spring Boot API** | `http://localhost:8081/api` | `SERVER_PORT`, `server.port=8081` |
| **Face Microservice** | `http://localhost:5001` | `FACE_VERIFICATION_SERVICE_URL`, `PORT=5001` |
| **MySQL Database** | `localhost:3307` or `3306` | `DB_URL` (`college_attendance` db) |

## Features

### 👨‍🏫 Faculty
- **Daily Punch-In/Punch-Out** — One-click attendance logging with real-time status
- **Attendance History** — View personal attendance records with status indicators
- **Leave Management** — Apply for Casual, Medical, OD, Earned, or Sick leaves
- **Profile Management** — Update phone, address, bio, and profile picture

### 🛡️ Admin (VC / Principal / TPO / HOD)
- **Real-Time Dashboard** — Live attendance statistics with charts
- **Department Breakdown** — Per-department attendance percentages
- **Leave Approvals** — Email-based approve/reject workflow with tokenized links
- **Faculty Monitoring** — View all faculty attendance in a searchable table

### 🔐 Developer
- **Full Access** — Manages system configuration and all entities
- **Auto-Seeding** — Developer account created on first run

---

## Project Structure

```
college-attendance/
├── database/
│   └── schema.sql                 # MySQL DDL + seed data + views + stored procedures
├── facultybackend/                # Spring Boot application
│   ├── pom.xml
│   └── src/main/java/com/college/attendance/
│       ├── CollegeAttendanceApplication.java
│       ├── config/
│       │   ├── SecurityConfig.java    # JWT + CORS + role-based access
│       │   └── DataSeeder.java        # Auto-seed developer + sample data
│       ├── controller/
│       │   ├── AuthController.java    # POST /api/auth/login
│       │   ├── AttendanceController.java  # Punch-in/out + admin stats
│       │   ├── LeaveController.java   # Leave apply + email approve/reject
│       │   └── ProfileController.java # Profile CRUD + picture upload
│       ├── dto/
│       │   ├── AuthRequest.java / AuthResponse.java
│       │   ├── AttendanceDTO.java
│       │   ├── LeaveRequestDTO.java
│       │   ├── UserProfileDTO.java
│       │   └── DashboardStatsDTO.java
│       ├── entity/
│       │   ├── User.java          # Implements UserDetails
│       │   ├── Department.java
│       │   ├── AttendanceLog.java
│       │   └── LeaveRequest.java
│       ├── enums/
│       │   ├── Role.java          # DEVELOPER, VC, PRINCIPAL, TPO, HOD, FACULTY
│       │   ├── AttendanceStatus.java
│       │   ├── LeaveType.java
│       │   └── LeaveStatus.java
│       ├── repository/            # Spring Data JPA repositories
│       ├── security/
│       │   ├── JwtUtil.java       # Token generation/validation
│       │   └── JwtAuthenticationFilter.java
│       └── service/
│           ├── AuthService.java
│           ├── AttendanceService.java
│           ├── LeaveService.java
│           └── EmailService.java  # Async email with Thymeleaf templates
│   └── src/main/resources/
│       ├── application.properties
│       └── templates/
│           ├── leave-request-email.html    # Email to HOD/Admin
│           ├── leave-approved-email.html   # Confirmation to faculty
│           └── leave-rejected-email.html   # Rejection to faculty
└── frontend/                      # React + Vite application
    ├── package.json
    ├── vite.config.js
    ├── tailwind.config.js
    └── src/
        ├── main.jsx
        ├── App.jsx                # Route definitions
        ├── index.css              # Tailwind + custom components
        ├── context/
        │   └── AuthContext.jsx    # Global auth state
        ├── services/
        │   └── api.js             # Axios + JWT interceptor
        ├── pages/
        │   ├── LoginPage.jsx
        │   ├── FacultyDashboard.jsx
        │   ├── AdminDashboard.jsx
        │   ├── LeaveManagement.jsx
        │   └── ProfilePage.jsx
        └── components/
            ├── common/
            │   ├── ProtectedRoute.jsx
            │   ├── AdminRoute.jsx
            │   └── Sidebar.jsx
            └── faculty/
                ├── PunchInWidget.jsx
                ├── AttendanceHistory.jsx
                ├── LeaveApplicationForm.jsx
                └── LeaveRequestsList.jsx
```

---

## Setup Instructions

### 1. Database Setup

```bash
# Login to MySQL (project default port is 3307, not the MySQL default 3306)
mysql -u root -p -P 3307

# Run the schema script
source database/schema.sql
```

### 2. Backend Setup

```bash
cd facultybackend

# Update application.properties with your MySQL credentials
# spring.datasource.username=root
# spring.datasource.password=your_password
# (datasource URL already targets localhost:3307/college_attendance)

# Build and run
./mvnw spring-boot:run
```

The backend starts on `http://localhost:8081/api`. The DataSeeder will auto-create:
The DataSeeder can create the initial developer account using
the credentials configured through environment variables.

For security:
- Configure `DEVELOPER_SEED_EMAIL` and `DEVELOPER_SEED_PASSWORD`.
- Never publish actual account passwords.
- Create or activate HOD and faculty accounts through the developer module.
- Change all initial credentials before production deployment.
### 3. Frontend Setup

```bash
cd frontend

# Install dependencies
npm install

# Start dev server
npm run dev
```

The frontend starts on `http://localhost:3000` with API proxy to the backend.

### 4. Email Configuration (Optional)

For the leave approval email workflow, configure SMTP in `application.properties`:

```properties
spring.mail.host=smtp.gmail.com
spring.mail.port=587
spring.mail.username=your-email@gmail.com
spring.mail.password=your-app-password
```

> Use [Gmail App Passwords](https://myaccount.google.com/apppasswords) for Gmail.

### 5. Face Verification Service (Optional)

The `face_verification/` microservice compares a captured face photo against the
stored profile photo (Flask, listens on **port 5001**).

```bash
cd face_verification
pip install -r requirements.txt
python face_service.py
```

> **Windows note:** `face_recognition` depends on `dlib`, which needs Visual
> Studio Build Tools to compile. If the `pip install -r requirements.txt` step
> fails with *"You must use Visual Studio to build a python extension on
> windows"*, install the prebuilt wheel instead:
>
> ```bash
> pip install dlib-bin                    # prebuilt dlib (no compiler needed)
> pip install --no-deps face_recognition  # skip building dlib again
> pip install face_recognition_models Click flask flask-cors numpy Pillow
> ```

Test it:

```bash
# 1. Health check -> {"status": "ok", "service": "face-verification"}
curl http://localhost:5001/health

# 2. Verify a captured photo against a stored profile photo
curl -X POST http://localhost:5001/verify-face \
  -H "Content-Type: application/json" \
  -d '{"captured_photo": "<base64-JPEG>", "reference_photo_path": "facultybackend/uploads/profile-pictures/<file>.jpg"}'

# Response: {"match": true/false, "confidence": 0-100, "distance": 0-1, "message": "..."}
```

---

## API Endpoints

### Authentication
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/auth/login` | Login and get JWT token |

### Attendance (Faculty)
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/attendance/punch-in` | Record punch-in |
| POST | `/api/attendance/punch-out` | Record punch-out |
| GET | `/api/attendance/today` | Get today's attendance |
| GET | `/api/attendance/history` | Get attendance history |

### Attendance (Admin)
| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/attendance/admin/all` | All faculty attendance today |
| GET | `/api/attendance/admin/stats` | Dashboard statistics |
| GET | `/api/attendance/admin/department/{id}` | Department attendance |

### Leave Management
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/leave/apply` | Apply for leave |
| GET | `/api/leave/my-leaves` | Get my leave requests |
| POST | `/api/leave/cancel/{id}` | Cancel pending leave |
| GET | `/api/leave/approve/{token}` | Approve leave (email link) |
| GET | `/api/leave/reject/{token}` | Reject leave (email link) |

### Profile
| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/profile` | Get current user profile |
| PUT | `/api/profile` | Update profile (phone, address, bio) |
| POST | `/api/profile/picture` | Upload profile picture |

---

## Role-Based Access

| Role | Access Level |
|------|-------------|
| **DEVELOPER** | Full system access, manages all entities |
| **VC** | View all departments, approve leaves |
| **PRINCIPAL** | View all departments, approve leaves |
| **TPO** | View training-related departments |
| **HOD** | Manage own department, approve faculty leaves |
| **FACULTY** | Punch in/out, apply for leave, manage profile |

---

## Email Approval Workflow

1. Faculty submits a leave request via the React app
2. Spring Boot generates a unique token and sends an email to the HOD and Principal
3. The email contains **[Approve]** and **[Reject]** buttons with tokenized URLs
4. When an admin clicks a link:
   - The token is validated (checks expiry + status)
   - Leave status is updated in MySQL
   - A confirmation email is sent to the faculty member

---

## License

MIT
