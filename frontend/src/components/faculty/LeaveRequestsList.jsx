import { HiCalendar, HiClock, HiCheckCircle, HiXCircle, HiExclamationCircle } from 'react-icons/hi';
import dayjs from 'dayjs';

const statusConfig = {
  PENDING: { color: 'text-yellow-600', bg: 'bg-yellow-50', icon: HiClock, label: 'Pending' },
  APPROVED: { color: 'text-green-600', bg: 'bg-green-50', icon: HiCheckCircle, label: 'Approved' },
  REJECTED: { color: 'text-red-600', bg: 'bg-red-50', icon: HiXCircle, label: 'Rejected' },
  CANCELLED: { color: 'text-gray-600', bg: 'bg-gray-50', icon: HiExclamationCircle, label: 'Cancelled' },
};

const leaveTypeColors = {
  CASUAL: 'bg-blue-100 text-blue-700',
  MEDICAL: 'bg-red-100 text-red-700',
  OD: 'bg-purple-100 text-purple-700',
  EARNED: 'bg-green-100 text-green-700',
  SICK: 'bg-orange-100 text-orange-700',
};

export default function LeaveRequestsList({ leaves = [], onCancel, isAdmin }) {
  if (leaves.length === 0) {
    return (
      <div className="text-center py-12">
        <HiCalendar className="w-16 h-16 text-gray-300 mx-auto mb-4" />
        <h3 className="text-[19px] font-medium text-gray-900 mb-1">No leave requests</h3>
        <p className="text-[15px] text-gray-500">No leave requests found for the selected filter.</p>
      </div>
    );
  }

  return (
    <div className="space-y-4">
      {leaves.map((leave) => {
        const config = statusConfig[leave.status] || statusConfig.PENDING;
        const Icon = config.icon;

        return (
          <div
            key={leave.id}
            className="card hover:shadow-md transition-shadow"
          >
            <div className="flex items-start justify-between">
              <div className="flex-1">
                <div className="flex items-center gap-3 mb-3">
                  {/* Status Badge */}
                  <span className={`inline-flex items-center gap-1 px-2.5 py-1 rounded-full text-[13px] font-medium ${config.bg} ${config.color}`}>
                    <Icon className="w-3.5 h-3.5" />
                    {config.label}
                  </span>

                  {/* Leave Type Badge */}
                  <span className={`px-2.5 py-1 rounded-full text-[13px] font-medium ${leaveTypeColors[leave.leaveType] || 'bg-gray-100 text-gray-700'}`}>
                    {leave.leaveType}
                  </span>

                  <span className="text-[13.5px] text-gray-400">
                    {leave.durationDays} day(s)
                  </span>
                </div>

                {/* Faculty Info (Admin View) */}
                {isAdmin && (
                  <div className="mb-2">
                    <p className="text-[16px] font-medium text-gray-900">{leave.facultyName}</p>
                    <p className="text-[14px] text-gray-500">{leave.employeeId} • {leave.departmentName}</p>
                  </div>
                )}

                {/* Dates */}
                <div className="flex items-center gap-2 text-[15px] text-gray-600 mb-2">
                  <HiCalendar className="w-4 h-4 text-gray-400" />
                  <span>
                    {dayjs(leave.startDate).format('MMM D, YYYY')} — {dayjs(leave.endDate).format('MMM D, YYYY')}
                  </span>
                </div>

                {/* Reason */}
                <p className="text-[14.5px] text-gray-600 bg-gray-50 rounded-lg p-3">
                  <span className="font-medium">Reason:</span> {leave.reason}
                </p>

                {/* Reviewer Comments */}
                {leave.reviewerComments && (
                  <div className="mt-2 p-3 rounded-lg bg-blue-50 text-[14.5px] text-blue-700">
                    <span className="font-medium">Reviewer Comments:</span> {leave.reviewerComments}
                  </div>
                )}

                {/* Timestamps */}
                <p className="text-[13px] text-gray-400 mt-2">
                  Applied {dayjs(leave.createdAt).format('MMM D, YYYY h:mm A')}
                  {leave.reviewedAt && (
                    <> • Reviewed {dayjs(leave.reviewedAt).format('MMM D, YYYY h:mm A')}</>
                  )}
                </p>
              </div>

              {/* Cancel Button (only for pending leaves) */}
              {!isAdmin && leave.status === 'PENDING' && (
                <button
                  onClick={() => onCancel(leave.id)}
                  className="ml-4 text-red-600 hover:text-red-700 text-[14.5px] font-medium px-3 py-1 rounded-lg hover:bg-red-50 transition-colors"
                >
                  Cancel
                </button>
              )}
            </div>
          </div>
        );
      })}
    </div>
  );
}
