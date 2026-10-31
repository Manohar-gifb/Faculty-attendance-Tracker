import axios from 'axios';

// ============================================================
// CONFIGURATION
// ============================================================
// Set USE_MOCK_API to true when testing without backend
// Set to false when connecting to real Spring Boot backend

const USE_MOCK_API = false; // <-- Set to true for frontend-only testing without backend

// In dev, Vite proxy handles /api → backend. In production, use absolute backend URL.
const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || '/api';
const SENDER_EMAIL = import.meta.env.VITE_SENDER_EMAIL || 'noreply@college.edu';
const APP_URL = import.meta.env.VITE_APP_URL || window.location.origin;
const APP_NAME = import.meta.env.VITE_APP_NAME || 'Faculty Sync Tracker';

// ============================================================
// STATIC UPLOADS (profile pictures)
// ------------------------------------------------------------
// Images are uploaded to the backend and stored at ./uploads (served by the
// backend at /uploads/**). In dev, the Vite proxy forwards /uploads to the
// backend, so relative URLs work as-is. In production, set VITE_UPLOADS_URL
// to the backend's /uploads URL (e.g. https://api.example.com/uploads) if the
// API lives on another origin; otherwise same-origin /uploads must be exposed
// by the reverse proxy serving the frontend.
// ============================================================

/**
 * Resolve a backend-stored image URL for <img src>.
 * - null/absolute/other paths pass through untouched,
 * - backend /uploads/... paths get the optional production base prefix.
 * Never exposes server file-system paths (the URL is the public static path).
 */
export function imageSrc(url) {
    if (!url) return url;
    const uploadsBase = import.meta.env.VITE_UPLOADS_URL;
    if (url.startsWith('/api/uploads/')) {
        return uploadsBase ? uploadsBase.replace(/\/$/, '') + url.replace(/^\/api/, '') : url;
    }
    if (url.startsWith('/uploads/')) {
        if (uploadsBase) {
            return uploadsBase.replace(/\/$/, '') + url;
        }
        // Backend is served under context path /api (server.servlet.context-path=/api)
        return `/api${url}`;
    }
    return url;
}

// ============================================================
// MOCK API (DEV-ONLY, LAZY-LOADED)
// ------------------------------------------------------------
// mockApi.js is NO LONGER statically imported here — a static import
// forced all ~870 lines of mock code into the production bundle even
// with USE_MOCK_API = false. It is now loaded lazily, only when
// USE_MOCK_API has been switched to true for offline frontend testing.
// ============================================================

let mockApiInstance = null;

async function getMock() {
    if (!USE_MOCK_API) return null;
    if (!mockApiInstance) {
        mockApiInstance = await import('./mockApi');
        mockApiInstance.enableMockAPI();
    }
    return mockApiInstance;
}

// ============================================================
// REAL API SETUP (when backend is ready)
// ============================================================

let api;

if (!USE_MOCK_API) {
    // Create axios instance
    api = axios.create({
        baseURL: API_BASE_URL,
        headers: {
            'Content-Type': 'application/json',
        },
    });

    // Request interceptor - add JWT token
    api.interceptors.request.use(
        (config) => {
            const token = localStorage.getItem('token');
            if (token) {
                config.headers.Authorization = `Bearer ${token}`;
            }
            return config;
        },
        (error) => {
            return Promise.reject(error);
        }
    );

    // Response interceptor - handle auth errors and network resilience
    api.interceptors.response.use(
        (response) => response,
        (error) => {
            if (error.response?.status === 401) {
                const currentPath = window.location.pathname;
                if (currentPath !== '/login' && currentPath !== '/set-password') {
                    localStorage.removeItem('token');
                    localStorage.removeItem('user');
                    window.location.href = '/login';
                }
            } else if (error.code === 'ECONNABORTED' || (error.message && error.message.toLowerCase().includes('timeout'))) {
                error.userFriendlyMessage = 'Request timed out. Please check your network connection and retry.';
            } else if (!error.response && error.message === 'Network Error') {
                error.userFriendlyMessage = 'Unable to connect to the server. Please verify the backend service is running.';
            }
            return Promise.reject(error);
        }
    );
}

// ============================================================
// AUTH API
// ============================================================
export const authAPI = {
    login: async (email, password) => {
        const mock = await getMock();
        if (mock) return mock.mockAuthAPI.login(email, password);
        return api.post('/auth/login', { email, password });
    },

    changePasswordFirstLogin: async (data) => {
        const mock = await getMock();
        if (mock) return mock.mockAuthAPI.changePasswordFirstLogin(data);
        return api.post('/auth/change-password-first', data);
    },

    changePassword: async (data) => {
        const mock = await getMock();
        if (mock) return mock.mockAuthAPI.changePassword(data);
        return api.post('/auth/change-password', data);
    },

    setupPassword: async (data) => {
        const mock = await getMock();
        if (mock) return mock.mockAuthAPI.setupPassword(data);
        return api.post('/auth/setup-password', data);
    },

    forgotPassword: async (email) => {
        const mock = await getMock();
        if (mock) return mock.mockAuthAPI.forgotPassword(email);
        return api.post('/auth/forgot-password', { email });
    },

    validateSetupToken: async (token) => {
        const mock = await getMock();
        if (mock) return mock.mockAuthAPI.validateSetupToken(token);
        return api.get(`/auth/setup-password/validate?token=${token}`);
    },
};

// ============================================================
// ATTENDANCE API
// ============================================================
export const attendanceAPI = {
    // ticket = short-lived single-use ticket returned /attendance/verify
    // when face + location verification passed (required for all roles
    // except DEVELOPER on the backend).
    punchIn: async (ticket) => {
        const mock = await getMock();
        if (mock) return mock.mockAttendanceAPI.punchIn();
        return api.post('/attendance/punch-in', ticket ? { verificationTicket: ticket } : {});
    },

    punchOut: async () => {
        const mock = await getMock();
        if (mock) return mock.mockAttendanceAPI.punchOut();
        return api.post('/attendance/punch-out');
    },

    getTodayAttendance: async () => {
        const mock = await getMock();
        if (mock) return mock.mockAttendanceAPI.getTodayAttendance();
        return api.get('/attendance/today');
    },

    getHistory: async (startDate, endDate) => {
        const mock = await getMock();
        if (mock) return mock.mockAttendanceAPI.getHistory(startDate, endDate);
        return api.get(`/attendance/history?startDate=${startDate}&endDate=${endDate}`);
    },

    getAllTodayAttendance: async () => {
        const mock = await getMock();
        if (mock) return mock.mockAttendanceAPI.getAllTodayAttendance();
        return api.get('/attendance/admin/all');
    },

    getDepartmentAttendance: async (deptId) => {
        const mock = await getMock();
        if (mock) return mock.mockAttendanceAPI.getDepartmentAttendance(deptId);
        return api.get(`/attendance/admin/department/${deptId}`);
    },

    getDashboardStats: async () => {
        const mock = await getMock();
        if (mock) return mock.mockAttendanceAPI.getDashboardStats();
        return api.get('/attendance/admin/stats');
    },

    verifyAttendance: (data) =>
        USE_MOCK_API
            ? Promise.resolve({ data: { verified: true, faceMatch: true, locationValid: true, message: 'Mock verification passed' } })
            : api.post('/attendance/verify', data),

    getLocationConfig: () =>
        USE_MOCK_API
            ? Promise.resolve({ data: { latitude: 12.9716, longitude: 77.5946, radiusMeters: 1000.0, maxAccuracyMeters: 150.0 } })
            : api.get('/attendance/admin/location-config'),

    updateLocationConfig: (config) =>
        USE_MOCK_API
            ? Promise.resolve({ data: { ...config, message: 'Location configuration updated successfully' } })
            : api.put('/attendance/admin/location-config', config),
};

// ============================================================
// LEAVE API
// ============================================================
export const leaveAPI = {
    applyLeave: async (data) => {
        const mock = await getMock();
        if (mock) return mock.mockLeaveAPI.applyLeave(data);
        return api.post('/leave/apply', data);
    },

    getMyLeaves: async () => {
        const mock = await getMock();
        if (mock) return mock.mockLeaveAPI.getMyLeaves();
        return api.get('/leave/my-leaves');
    },

    cancelLeave: async (leaveId) => {
        const mock = await getMock();
        if (mock) return mock.mockLeaveAPI.cancelLeave(leaveId);
        return api.post(`/leave/cancel/${leaveId}`);
    },

    getPendingLeaves: async (departmentId) => {
        const mock = await getMock();
        if (mock) return mock.mockLeaveAPI.getPendingLeaves(departmentId);
        return api.get(`/leave/admin/pending${departmentId ? `?departmentId=${departmentId}` : ''}`);
    },

    getPendingForMe: async () => {
        const mock = await getMock();
        if (mock) return mock.mockLeaveAPI.getPendingLeaves();
        return api.get('/leave/admin/pending-for-me');
    },

    getDepartmentLeaves: async (deptId) => {
        const mock = await getMock();
        if (mock) return mock.mockLeaveAPI.getDepartmentLeaves(deptId);
        return api.get(`/leave/admin/department/${deptId}`);
    },

    approveLeave: async (leaveId, comments) => {
        const mock = await getMock();
        if (mock) return mock.mockLeaveAPI.approveLeave(leaveId, comments);
        return api.post(`/leave/admin/approve/${leaveId}`, { comments: comments || '' });
    },

    rejectLeave: async (leaveId, comments) => {
        const mock = await getMock();
        if (mock) return mock.mockLeaveAPI.rejectLeave(leaveId, comments);
        return api.post(`/leave/admin/reject/${leaveId}`, { comments: comments || '' });
    },
};

// ============================================================
// PROFILE API
// ============================================================
export const profileAPI = {
    getProfile: async () => {
        const mock = await getMock();
        if (mock) return mock.mockProfileAPI.getProfile();
        return api.get('/profile');
    },

    updateProfile: async (data) => {
        const mock = await getMock();
        if (mock) return mock.mockProfileAPI.updateProfile(data);
        return api.put('/profile', data);
    },

    uploadPicture: async (file) => {
        const mock = await getMock();
        if (mock) {
            return mock.mockProfileAPI.uploadPicture(file);
        }

        const formData = new FormData();
        formData.append('file', file);
        return api.post('/profile/picture', formData, {
            headers: { 'Content-Type': 'multipart/form-data' },
        });
    },
};

// ============================================================
// TEAM API
// ============================================================
export const teamAPI = {
    getDepartmentFaculty: async () => {
        const mock = await getMock();
        if (mock) return mock.mockTeamAPI.getDepartmentFaculty();
        return api.get('/team/department');
    },

    getAllFaculty: async () => {
        const mock = await getMock();
        if (mock) return mock.mockTeamAPI.getAllFaculty();
        return api.get('/team/all');
    },
};

// ============================================================
// DEVELOPER API (Account Management)
// ============================================================
export const developerAPI = {
    getAllAccounts: async () => {
        const mock = await getMock();
        if (mock) return mock.mockDeveloperAPI.getAllAccounts();
        return api.get('/developer/accounts');
    },

    getDepartments: async () => {
        const mock = await getMock();
        if (mock) return mock.mockDeveloperAPI.getDepartments();
        return api.get('/developer/departments');
    },

    getApprovers: async () => {
        const mock = await getMock();
        if (mock) return mock.mockDeveloperAPI.getApprovers();
        return api.get('/developer/approvers');
    },

    createAccount: async (data, photoBlob = null) => {
        const mock = await getMock();
        if (mock) {
            return mock.mockDeveloperAPI.createAccount(data);
        }
        if (!photoBlob) {
            return api.post('/developer/accounts', data);
        }
        const fd = new FormData();
        fd.append('request', new Blob([JSON.stringify(data)], { type: 'application/json' }));

        let blob = photoBlob;
        if (typeof photoBlob === 'string' && photoBlob.startsWith('data:')) {
            const arr = photoBlob.split(',');
            const mime = arr[0].match(/:(.*?);/)?.[1] || 'image/jpeg';
            const bstr = atob(arr[1]);
            let n = bstr.length;
            const u8arr = new Uint8Array(n);
            while (n--) {
                u8arr[n] = bstr.charCodeAt(n);
            }
            blob = new Blob([u8arr], { type: mime });
        }

        fd.append('photo', blob, 'photo.jpg');
        return api.post('/developer/accounts', fd, {
            headers: { 'Content-Type': 'multipart/form-data' },
        });
    },

    updateAccount: (accountId, data) =>
        USE_MOCK_API
            ? Promise.resolve({ data: { message: 'Updated' } })
            : api.put(`/developer/accounts/${accountId}`, data),

    uploadAccountPhoto: (accountId, photoBlob) => {
        if (USE_MOCK_API) {
            return Promise.resolve({ data: { message: 'Photo updated' } });
        }
        const fd = new FormData();
        let blob = photoBlob;
        if (typeof photoBlob === 'string' && photoBlob.startsWith('data:')) {
            const arr = photoBlob.split(',');
            const mime = arr[0].match(/:(.*?);/)?.[1] || 'image/jpeg';
            const bstr = atob(arr[1]);
            let n = bstr.length;
            const u8arr = new Uint8Array(n);
            while (n--) {
                u8arr[n] = bstr.charCodeAt(n);
            }
            blob = new Blob([u8arr], { type: mime });
        }
        fd.append('photo', blob, 'photo.jpg');
        return api.post(`/developer/accounts/${accountId}/photo`, fd, {
            headers: { 'Content-Type': 'multipart/form-data' },
        });
    },

    updateApprover: async (userId, data) => {
        const mock = await getMock();
        if (mock) return mock.mockDeveloperAPI.updateApprover(userId, data);
        return api.put(`/developer/accounts/${userId}/approver`, data);
    },

    deleteAccount: async (accountId) => {
        const mock = await getMock();
        if (mock) return mock.mockDeveloperAPI.deleteAccount(accountId);
        return api.delete(`/developer/accounts/${accountId}`);
    },

    getStats: async () => {
        const mock = await getMock();
        if (mock) return mock.mockDeveloperAPI.getStats();
        return api.get('/developer/stats');
    },

    // ============================================================
    // Paged / filtered account list (backend pagination & sorting)
    // ============================================================
    getAccountsPage: (params = {}) =>
        api.get('/developer/accounts/search', { params }),

    // ============================================================
    // Account activation lifecycle (real backend operations)
    // ============================================================
    activateAccount: (accountId) =>
        api.post(`/developer/accounts/${accountId}/activate`),

    deactivateAccount: (accountId) =>
        api.post(`/developer/accounts/${accountId}/deactivate`),

    resendActivation: (accountId) =>
        api.post(`/developer/accounts/${accountId}/resend-activation`),

    resetPassword: (accountId) =>
        api.post(`/developer/accounts/${accountId}/reset-password`),

    // ============================================================
    // Department management (Developer module)
    // ============================================================
    getAllDepartments: () =>
        api.get('/developer/departments/all'),

    createDepartment: (data) =>
        api.post('/developer/departments', data),

    updateDepartment: (departmentId, data) =>
        api.put(`/developer/departments/${departmentId}`, data),

    deleteDepartment: (departmentId) =>
        api.delete(`/developer/departments/${departmentId}`),

    // ============================================================
    // Audit trail (recent administrative actions)
    // ============================================================
    getAuditLogs: (limit = 25) =>
        api.get('/developer/audit-logs', { params: { limit } }),
};

// ============================================================
// APP CONFIG (for email templates, login URLs, etc.)
// ============================================================
export const appConfig = {
    senderEmail: SENDER_EMAIL,
    appUrl: APP_URL,
    appName: APP_NAME,
    loginUrl: `${APP_URL}/login`,
};

export default api;
