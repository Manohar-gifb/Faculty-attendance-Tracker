import { HiCheckCircle, HiXCircle, HiExclamationCircle, HiClock } from 'react-icons/hi';
import dayjs from 'dayjs';

const statusConfig = {
  PRESENT: { color: 'text-green-600', bg: 'bg-green-50', icon: HiCheckCircle, label: 'Present' },
  ABSENT: { color: 'text-red-600', bg: 'bg-red-50', icon: HiXCircle, label: 'Absent' },
  LATE: { color: 'text-yellow-600', bg: 'bg-yellow-50', icon: HiExclamationCircle, label: 'Late' },
  HALF_DAY: { color: 'text-orange-600', bg: 'bg-orange-50', icon: HiClock, label: 'Half Day' },
  ON_LEAVE: { color: 'text-blue-600', bg: 'bg-blue-50', icon: HiClock, label: 'On Leave' },
};

export default function AttendanceHistory({ records = [] }) {
  if (records.length === 0) {
    return (
      <div className="text-center py-8">
        <HiClock className="w-12 h-12 text-gray-300 mx-auto mb-3" />
        <p className="text-gray-500">No attendance records found</p>
      </div>
    );
  }

  return (
    <div className="space-y-3 max-h-96 overflow-y-auto">
      {records.map((record) => {
        const config = statusConfig[record.status] || statusConfig.ABSENT;
        const Icon = config.icon;

        return (
          <div
            key={record.id}
            className="flex items-center justify-between p-3 rounded-lg bg-gray-50 hover:bg-gray-100 transition-colors"
          >
            <div className="flex items-center gap-3">
              <div className={`p-2 rounded-lg ${config.bg}`}>
                <Icon className={`w-4 h-4 ${config.color}`} />
              </div>
              <div>
                <p className="text-[15px] font-medium text-gray-900">
                  {dayjs(record.attendanceDate).format('ddd, MMM D')}
                </p>
                <p className="text-[13.5px] text-gray-500">
                  {config.label}
                  {record.isLate && ` • ${record.lateMinutes} min late`}
                </p>
              </div>
            </div>
            <div className="text-right">
              {record.punchInTime && (
                <p className="text-[14.5px] text-gray-700">
                  {dayjs(record.punchInTime).format('hh:mm A')}
                  {record.punchOutTime && (
                    <> → {dayjs(record.punchOutTime).format('hh:mm A')}</>
                  )}
                </p>
              )}
              {record.workingHours > 0 && (
                <p className="text-[13px] text-gray-500">{record.workingHours}h</p>
              )}
            </div>
          </div>
        );
      })}
    </div>
  );
}
