// ============================================================
// MOCK API FOR TESTING (No Backend Required)
// ============================================================
// This file simulates backend responses for testing purposes.
// Remove this file when connecting to the real Spring Boot backend.
// ============================================================

const MOCK_USERS = [
    // DEVELOPER
    {
        id: 1,
        firstName: "System",
        lastName: "Admin",
        fullName: "System Admin",
        email: "Dev@college.edu",
        password: "Dev@12345",
        role: "DEVELOPER",
        departmentName: "Administration",
        departmentId: 1,
        employeeId: "DEV001",
        phone: "555-0100",
        firstLogin: false,
        profilePictureUrl: null,
    },
    // HOD - mapped to Principal for leave approval
    {
        id: 2,
        firstName: "Rajesh",
        lastName: "Kumar",
        fullName: "Rajesh Kumar",
        email: "rajesh.kumar@college.edu",
        password: "Hod@1234",
        role: "HOD",
        departmentName: "Computer Science",
        departmentId: 2,
        employeeId: "HOD001",
        phone: "555-0101",
        firstLogin: false,
        profilePictureUrl: null,
        leaveApproverId: 3,
        leaveApproverName: "Suresh Babu (PRINCIPAL)",
        secondaryApproverId: 4,
        secondaryApproverName: "Venkatesh Reddy (VC)",
    },
    // PRINCIPAL - mapped to VC for leave approval
    {
        id: 3,
        firstName: "Suresh",
        lastName: "Babu",
        fullName: "Suresh Babu",
        email: "suresh.babu@college.edu",
        password: "Principal@123",
        role: "PRINCIPAL",
        departmentName: "Administration",
        departmentId: 1,
        employeeId: "PRN001",
        phone: "555-0102",
        firstLogin: false,
        profilePictureUrl: null,
        leaveApproverId: 4,
        leaveApproverName: "Venkatesh Reddy (VC)",
        secondaryApproverId: null,
        secondaryApproverName: null,
    },
    // VC - highest authority, no approver needed
    {
        id: 4,
        firstName: "Venkatesh",
        lastName: "Reddy",
        fullName: "Venkatesh Reddy",
        email: "venkatesh.reddy@college.edu",
        password: "Vc@1234",
        role: "VC",
        departmentName: "Administration",
        departmentId: 1,
        employeeId: "VC001",
        phone: "555-0103",
        firstLogin: false,
        profilePictureUrl: null,
        leaveApproverId: null,
        leaveApproverName: null,
        secondaryApproverId: null,
        secondaryApproverName: null,
    },
    // TPO - mapped to Principal for leave approval
    {
        id: 5,
        firstName: "Priya",
        lastName: "Sharma",
        fullName: "Priya Sharma",
        email: "priya.sharma@college.edu",
        password: "Tpo@1234",
        role: "TPO",
        departmentName: "Training & Placement",
        departmentId: 3,
        employeeId: "TPO001",
        phone: "555-0104",
        firstLogin: false,
        profilePictureUrl: null,
        leaveApproverId: 3,
        leaveApproverName: "Suresh Babu (PRINCIPAL)",
        secondaryApproverId: 4,
        secondaryApproverName: "Venkatesh Reddy (VC)",
    },
    // FACULTY (First-time login - will need password change)
    {
        id: 6,
        firstName: "Anita",
        lastName: "Patel",
        fullName: "Anita Patel",
        email: "anita.patel@college.edu",
        password: "Faculty@123",
        role: "FACULTY",
        departmentName: "Computer Science",
        departmentId: 2,
        employeeId: "FAC001",
        phone: "555-0105",
        firstLogin: true,
        profilePictureUrl: null,
        leaveApproverId: 2,
        leaveApproverName: "Rajesh Kumar (HOD - Computer Science)",
        secondaryApproverId: 3,
        secondaryApproverName: "Suresh Babu (PRINCIPAL)",
    },
    // FACULTY 2
    {
        id: 7,
        firstName: "Vikram",
        lastName: "Singh",
        fullName: "Vikram Singh",
        email: "vikram.singh@college.edu",
        password: "Faculty@123",
        role: "FACULTY",
        departmentName: "Information Technology",
        departmentId: 4,
        employeeId: "FAC002",
        phone: "555-0106",
        firstLogin: false,
        profilePictureUrl: null,
        leaveApproverId: null,
        leaveApproverName: null,
        secondaryApproverId: null,
        secondaryApproverName: null,
    },
    // FACULTY 3
    {
        id: 8,
        firstName: "Meena",
        lastName: "Iyer",
        fullName: "Meena Iyer",
        email: "meena.iyer@college.edu",
        password: "Faculty@123",
        role: "FACULTY",
        departmentName: "Electronics & Communication",
        departmentId: 5,
        employeeId: "FAC003",
        phone: "555-0107",
        firstLogin: false,
        profilePictureUrl: null,
        leaveApproverId: null,
        leaveApproverName: null,
        secondaryApproverId: null,
        secondaryApproverName: null,
    },
];

const MOCK_DEPARTMENTS = [
    { id: 1, name: "Administration" },
    { id: 2, name: "Computer Science" },
    { id: 3, name: "Training & Placement" },
    { id: 4, name: "Information Technology" },
    { id: 5, name: "Electronics & Communication" },
    { id: 6, name: "Mechanical Engineering" },
    { id: 7, name: "Civil Engineering" },
];

// ============================================================
// PERSISTENT STATE (survives across calls within a session)
// ============================================================

// Store for created accounts (starts with seed data)
let accounts = [...MOCK_USERS];

// Store for attendance logs keyed by "userId-date"
const attendanceStore = {};

// Store for leave requests
let leaveStore = [];
let leaveIdCounter = 100;

// Track current logged-in user id (updated on login)
let currentUserId = null;

// Mock delay to simulate network latency
const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

// ============================================================
// HELPERS
// ============================================================

const getTodayString = () => {
    const now = new Date();
    const y = now.getFullYear();
    const m = String(now.getMonth() + 1).padStart(2, "0");
    const d = String(now.getDate()).padStart(2, "0");
    return `${y}-${m}-${d}`;
};

const getAttendanceKey = (userId, date) => `${userId}-${date || getTodayString()}`;

const generateToken = (user) => {
    const payload = {
        userId: user.id,
        email: user.email,
        role: user.role,
        exp: Date.now() + 24 * 60 * 60 * 1000,
    };
    return btoa(JSON.stringify(payload));
};

const findUserById = (id) => accounts.find((u) => u.id === id);

// ============================================================
// MOCK API FUNCTIONS
// ============================================================

// Store for setup tokens: token -> { userId, expiry }
const setupTokenStore = {};

export const mockAuthAPI = {
    login: async (email, password) => {
        await delay(500);

        const user = accounts.find(
            (u) => u.email === email && u.password === password
        );

        if (!user) {
            throw {
                response: {
                    status: 401,
                    data: { message: "Invalid email or password" },
                },
            };
        }

        // Block login if user hasn't set their password yet
        if (user.passwordSetupToken) {
            throw {
                response: {
                    status: 403,
                    data: { message: "Please set your password first. Check your email for the setup link." },
                },
            };
        }

        // Track logged-in user
        currentUserId = user.id;

        return {
            data: {
                token: generateToken(user),
                userId: user.id,
                fullName: user.fullName,
                firstName: user.firstName,
                lastName: user.lastName,
                email: user.email,
                role: user.role,
                departmentName: user.departmentName,
                departmentId: user.departmentId,
                employeeId: user.employeeId,
                phone: user.phone,
                firstLogin: user.firstLogin,
                profilePictureUrl: user.profilePictureUrl,
                message: user.firstLogin
                    ? "Please change your password before continuing"
                    : null,
            },
        };
    },

    changePasswordFirstLogin: async (data) => {
        await delay(300);
        // Update the current user's firstLogin flag
        const user = findUserById(currentUserId);
        if (user) {
            user.firstLogin = false;
            if (data.newPassword) user.password = data.newPassword;
        }
        return { data: { message: "Password changed successfully" } };
    },

    forgotPassword: async (email) => {
        await delay(500);
        // Same anti-enumeration contract as the backend: identical generic
        // response whether or not the account exists.
        return {
            data: {
                message:
                    "If an account exists for that email address, a password reset link has " +
                    "been sent. The link expires in 24 hours and can be used only once. " +
                    "If the email does not arrive, contact your administrator.",
            },
        };
    },

    changePassword: async (data) => {
        await delay(300);
        return { data: { message: "Password changed successfully" } };
    },

    setupPassword: async (data) => {
        await delay(400);

        const { token, newPassword, confirmPassword } = data;

        if (!token || !newPassword) {
            throw {
                response: {
                    status: 400,
                    data: { message: "Token and new password are required" },
                },
            };
        }

        if (newPassword !== confirmPassword) {
            throw {
                response: {
                    status: 400,
                    data: { message: "Passwords do not match" },
                },
            };
        }

        const tokenData = setupTokenStore[token];
        if (!tokenData) {
            throw {
                response: {
                    status: 400,
                    data: { message: "Invalid or expired setup link" },
                },
            };
        }

        if (new Date(tokenData.expiry) < new Date()) {
            delete setupTokenStore[token];
            throw {
                response: {
                    status: 400,
                    data: { message: "This setup link has expired. Please contact the administrator." },
                },
            };
        }

        // Find the user and update their password
        const user = accounts.find((u) => u.id === tokenData.userId);
        if (user) {
            user.password = newPassword;
            user.firstLogin = false;
        }

        // Remove the used token
        delete setupTokenStore[token];

        return { data: { message: "Password set successfully. You can now log in." } };
    },

    validateSetupToken: async (token) => {
        await delay(300);

        const tokenData = setupTokenStore[token];
        if (!tokenData) {
            return { data: { valid: false } };
        }

        if (new Date(tokenData.expiry) < new Date()) {
            delete setupTokenStore[token];
            return { data: { valid: false } };
        }

        const user = accounts.find((u) => u.id === tokenData.userId);
        if (!user) {
            return { data: { valid: false } };
        }

        return {
            data: {
                valid: true,
                fullName: user.fullName,
                email: user.email,
                employeeId: user.employeeId,
                role: user.role,
            },
        };
    },
};

export const mockDeveloperAPI = {
    getAllAccounts: async () => {
        await delay(400);
        const safeAccounts = accounts.map(({ password, passwordSetupToken, passwordSetupTokenExpiry, ...rest }) => {
            return { ...rest, passwordSetupPending: !!passwordSetupToken };
        });
        return { data: safeAccounts };
    },

    getDepartments: async () => {
        await delay(200);
        return { data: MOCK_DEPARTMENTS };
    },

    getApprovers: async () => {
        await delay(200);
        const approvers = accounts
            .filter((u) => ["HOD", "TPO", "PRINCIPAL", "VC"].includes(u.role))
            .map(({ password, ...rest }) => rest);
        return { data: approvers };
    },

    createAccount: async (data) => {
        await delay(500);

        if (accounts.find((u) => u.email === data.email)) {
            throw {
                response: {
                    status: 400,
                    data: { message: "Email already registered" },
                },
            };
        }

        const newId = Math.max(...accounts.map((u) => u.id)) + 1;
        // Generate a setup token (valid for 24 hours)
        const setupToken = crypto.randomUUID ? crypto.randomUUID() : `${Date.now()}-${Math.random().toString(36).slice(2)}`;
        const setupExpiry = new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString();
        setupTokenStore[setupToken] = { userId: newId, expiry: setupExpiry };

        // Placeholder password — user will set their own via the setup link
        const placeholderPassword = `PendingSetup_${Math.random().toString(36).slice(2, 10)}`;

        console.log(
            `%c📧 [MOCK EMAIL] Setup password link sent to ${data.email}`, "color: #10b981; font-weight: bold;",
            `\n   Link: http://localhost:3000/set-password?token=${setupToken}\n   Valid for: 24 hours`,
            `\n   Token: ${setupToken}`
        );

        const newAccount = {
            id: newId,
            firstName: data.firstName,
            lastName: data.lastName,
            fullName: `${data.firstName} ${data.lastName}`,
            email: data.email,
            password: placeholderPassword,
            role: data.role,
            departmentName:
                MOCK_DEPARTMENTS.find((d) => d.id == data.departmentId)?.name ||
                "Unknown",
            departmentId: data.departmentId,
            employeeId: data.employeeId || `EMP${newId}`,
            phone: data.phone || "",
            firstLogin: true,
            profilePictureUrl: null,
            passwordSetupToken: setupToken,
            passwordSetupTokenExpiry: setupExpiry,
            leaveApproverId: data.leaveApproverId || null,
            leaveApproverName: data.leaveApproverId
                ? accounts.find((u) => u.id == data.leaveApproverId)?.fullName
                : null,
            secondaryApproverId: data.secondaryApproverId || null,
            secondaryApproverName: data.secondaryApproverId
                ? accounts.find((u) => u.id == data.secondaryApproverId)?.fullName
                : null,
        };

        accounts.push(newAccount);

        return {
            data: {
                message: "Account created successfully",
                email: data.email,
            },
        };
    },

    deleteAccount: async (accountId) => {
        await delay(300);
        accounts = accounts.filter((u) => u.id !== accountId);
        return { data: { message: "Account deleted successfully" } };
    },

    updateApprover: async (userId, data) => {
        await delay(300);
        const user = accounts.find((u) => u.id == userId);
        if (user) {
            user.leaveApproverId = data.leaveApproverId;
            user.secondaryApproverId = data.secondaryApproverId;
        }
        return { data: { message: "Approver updated successfully" } };
    },

    getStats: async () => {
        await delay(200);
        const faculty = accounts.filter((u) => u.role === "FACULTY").length;
        const admins = accounts.filter((u) => u.role !== "FACULTY").length;
        const depts = new Set(accounts.map((u) => u.departmentName)).size;
        return {
            data: {
                total: accounts.length,
                faculty,
                admins,
                departments: depts,
            },
        };
    },
};

export const mockAttendanceAPI = {
    punchIn: async () => {
        await delay(300);

        const userId = currentUserId || 1;
        const today = getTodayString();
        const key = getAttendanceKey(userId, today);
        const now = new Date().toISOString();

        // Check if already punched in today
        if (attendanceStore[key] && attendanceStore[key].punchInTime) {
            throw {
                response: {
                    status: 400,
                    data: { message: "Already punched in today" },
                },
            };
        }

        attendanceStore[key] = {
            userId,
            attendanceDate: today,
            punchInTime: now,
            punchOutTime: null,
            workingHours: 0,
            status: "PRESENT",
            isLate: false,
            lateMinutes: 0,
        };

        return {
            data: {
                ...attendanceStore[key],
                hasPunchedIn: true,
                hasPunchedOut: false,
            },
        };
    },

    punchOut: async () => {
        await delay(300);

        const userId = currentUserId || 1;
        const today = getTodayString();
        const key = getAttendanceKey(userId, today);
        const now = new Date().toISOString();

        if (!attendanceStore[key] || !attendanceStore[key].punchInTime) {
            throw {
                response: {
                    status: 400,
                    data: { message: "You haven't punched in today" },
                },
            };
        }

        const punchIn = new Date(attendanceStore[key].punchInTime);
        const punchOut = new Date(now);
        const hours = Math.round(((punchOut - punchIn) / (1000 * 60 * 60)) * 100) / 100;

        attendanceStore[key].punchOutTime = now;
        attendanceStore[key].workingHours = hours;

        return {
            data: {
                ...attendanceStore[key],
                hasPunchedIn: true,
                hasPunchedOut: true,
            },
        };
    },

    getTodayAttendance: async () => {
        await delay(200);

        const userId = currentUserId || 1;
        const today = getTodayString();
        const key = getAttendanceKey(userId, today);
        const record = attendanceStore[key];

        if (record) {
            return {
                data: {
                    ...record,
                    hasPunchedIn: !!record.punchInTime,
                    hasPunchedOut: !!record.punchOutTime,
                },
            };
        }

        return {
            data: {
                hasPunchedIn: false,
                hasPunchedOut: false,
                punchInTime: null,
                punchOutTime: null,
                workingHours: 0,
                status: null,
                isLate: false,
                lateMinutes: 0,
            },
        };
    },

    getHistory: async (startDate, endDate) => {
        await delay(300);

        const userId = currentUserId || 1;
        const records = [];

        // Iterate through dates and collect attendance records
        const start = new Date(startDate);
        const end = new Date(endDate);

        for (let d = new Date(start); d <= end; d.setDate(d.getDate() + 1)) {
            const dateStr = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
            const key = getAttendanceKey(userId, dateStr);
            if (attendanceStore[key]) {
                records.push({
                    ...attendanceStore[key],
                    attendanceDate: dateStr,
                });
            }
        }

        return { data: records };
    },

    getAllTodayAttendance: async () => {
        await delay(300);
        const today = getTodayString();
        const records = Object.values(attendanceStore).filter(
            (r) => r.attendanceDate === today
        );
        return { data: records };
    },

    getDepartmentAttendance: async (deptId) => {
        await delay(300);
        return { data: [] };
    },

    getDashboardStats: async () => {
        await delay(200);
        const today = getTodayString();
        const todayRecords = Object.values(attendanceStore).filter(
            (r) => r.attendanceDate === today
        );
        return {
            data: {
                totalFaculty: accounts.filter((u) => u.role === "FACULTY").length,
                presentToday: todayRecords.filter((r) => r.status === "PRESENT").length,
                absentToday: 0,
                onLeave: 0,
            },
        };
    },
};

export const mockLeaveAPI = {
    applyLeave: async (data) => {
        await delay(400);

        const userId = currentUserId || 1;
        const user = findUserById(userId);

        const newLeave = {
            id: leaveIdCounter++,
            userId,
            facultyName: user?.fullName || "Unknown",
            employeeId: user?.employeeId || "N/A",
            departmentName: user?.departmentName || "N/A",
            leaveType: data.leaveType,
            startDate: data.startDate || data.fromDate,
            endDate: data.endDate || data.toDate,
            fromDate: data.fromDate || data.startDate,
            toDate: data.toDate || data.endDate,
            reason: data.reason,
            status: "PENDING",
            durationDays: (() => {
                const s = new Date(data.startDate || data.fromDate);
                const e = new Date(data.endDate || data.toDate);
                return Math.floor((e - s) / (1000 * 60 * 60 * 24)) + 1;
            })(),
            reviewerComments: null,
            reviewedAt: null,
            createdAt: new Date().toISOString(),
        };

        leaveStore.push(newLeave);

        return { data: newLeave };
    },

    getMyLeaves: async () => {
        await delay(300);
        const userId = currentUserId || 1;
        const myLeaves = leaveStore.filter((l) => l.userId === userId);
        return { data: myLeaves };
    },

    cancelLeave: async (leaveId) => {
        await delay(300);
        const leave = leaveStore.find((l) => l.id === leaveId);
        if (leave) {
            leave.status = "CANCELLED";
        }
        return { data: { message: "Leave cancelled" } };
    },

    getPendingLeaves: async (departmentId) => {
        await delay(300);
        // For admins/HODs: return all PENDING leaves (or filter by department)
        let pending = leaveStore.filter((l) => l.status === "PENDING");
        return { data: pending };
    },

    getPendingForMe: async () => {
        await delay(300);
        const userId = currentUserId || 1;
        // Return pending leaves from users who have this user as approver
        const pending = leaveStore.filter((l) => {
            if (l.status !== "PENDING") return false;
            const leaveUser = findUserById(l.userId);
            if (!leaveUser) return false;
            return (
                leaveUser.leaveApproverId === userId ||
                leaveUser.secondaryApproverId === userId
            );
        });
        return { data: pending };
    },

    getDepartmentLeaves: async (deptId) => {
        await delay(300);
        return { data: [] };
    },

    approveLeave: async (leaveId, comments) => {
        await delay(300);
        const leave = leaveStore.find((l) => l.id === leaveId);
        if (leave) {
            leave.status = "APPROVED";
            leave.reviewerComments = comments || "";
            leave.reviewedAt = new Date().toISOString();
        }
        return { data: { message: "Leave approved successfully" } };
    },

    rejectLeave: async (leaveId, comments) => {
        await delay(300);
        const leave = leaveStore.find((l) => l.id === leaveId);
        if (leave) {
            leave.status = "REJECTED";
            leave.reviewerComments = comments || "";
            leave.reviewedAt = new Date().toISOString();
        }
        return { data: { message: "Leave rejected successfully" } };
    },
};

export const mockProfileAPI = {
    getProfile: async () => {
        await delay(300);
        const userId = currentUserId || 1;
        const user = findUserById(userId);
        if (!user) {
            throw {
                response: {
                    status: 404,
                    data: { message: "User not found" },
                },
            };
        }
        return {
            data: {
                id: user.id,
                firstName: user.firstName,
                lastName: user.lastName,
                email: user.email,
                role: user.role,
                departmentName: user.departmentName,
                phone: user.phone,
                employeeId: user.employeeId,
                address: "",
                bio: "",
                profilePictureUrl: user.profilePictureUrl,
            },
        };
    },

    updateProfile: async (data) => {
        await delay(300);
        const userId = currentUserId || 1;
        const user = findUserById(userId);
        if (user && data.phone) user.phone = data.phone;
        return { data: { ...data, message: "Profile updated" } };
    },

    uploadPicture: async (file) => {
        await delay(500);
        return {
            data: {
                profilePictureUrl: URL.createObjectURL(file),
            },
        };
    },
};

export const mockTeamAPI = {
    getDepartmentFaculty: async () => {
        await delay(300);
        const userId = currentUserId || 1;
        const user = findUserById(userId);
        // Return faculty from the same department
        const deptId = user?.departmentId;
        const faculty = accounts
            .filter((u) => u.departmentId === deptId && u.id !== userId)
            .map(({ password, ...rest }) => rest);
        return { data: faculty };
    },

    getAllFaculty: async () => {
        await delay(300);
        const all = accounts.map(({ password, ...rest }) => {
            // Check if this user has a punch-in record today
            const key = getAttendanceKey(rest.id, getTodayString());
            const attendance = attendanceStore[key];
            return {
                ...rest,
                isClockedIn: attendance ? !!attendance.punchInTime && !attendance.punchOutTime : false,
                punchInTime: attendance?.punchInTime || null,
            };
        });
        return { data: all };
    },
};

// ============================================================
// MOCK API INTERCEPTOR
// ============================================================

export const enableMockAPI = () => {
    console.log(
        "%c🧪 MOCK API ENABLED - Using test data (no backend required)",
        "color: #f59e0b; font-weight: bold; font-size: 14px;"
    );
    console.log(
        "%c📋 Test credentials:",
        "color: #3b82f6; font-weight: bold;"
    );
    console.log("   ┌─────────────────────────────────────────────────────┐");
    console.log("   │ Role      │ Email                          │ Password  │");
    console.log("   ├─────────────────────────────────────────────────────┤");
    console.log("   │ Developer │ developer@college.edu          │ Dev@1234  │");
    console.log("   │ HOD       │ rajesh.kumar@college.edu       │ Hod@1234  │");
    console.log("   │ Principal │ suresh.babu@college.edu        │ Principal@123│");
    console.log("   │ VC        │ venkatesh.reddy@college.edu    │ Vc@1234   │");
    console.log("   │ TPO       │ priya.sharma@college.edu       │ Tpo@1234  │");
    console.log("   │ Faculty*  │ anita.patel@college.edu        │ Faculty@123│");
    console.log("   │ Faculty   │ vikram.singh@college.edu       │ Faculty@123│");
    console.log("   │ Faculty   │ meena.iyer@college.edu         │ Faculty@123│");
    console.log("   └─────────────────────────────────────────────────────┘");
    console.log("   * First-time login (requires password change)");
};
