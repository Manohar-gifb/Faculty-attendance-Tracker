import { useState } from 'react';
import { HiX } from 'react-icons/hi';
import dayjs from 'dayjs';

export default function LeaveApplicationForm({ onSubmit, onCancel }) {
  const [formData, setFormData] = useState({
    leaveType: '',
    startDate: '',
    endDate: '',
    reason: '',
  });

  const handleChange = (e) => {
    const { name, value } = e.target;
    setFormData((prev) => ({ ...prev, [name]: value }));
  };

  const handleSubmit = (e) => {
    e.preventDefault();

    if (!formData.leaveType || !formData.startDate || !formData.endDate || !formData.reason) {
      return;
    }

    if (dayjs(formData.endDate).isBefore(dayjs(formData.startDate))) {
      return;
    }

    onSubmit({
      ...formData,
      startDate: formData.startDate,
      endDate: formData.endDate,
    });
  };

  const leaveTypes = [
    { value: 'CASUAL', label: 'Casual Leave', emoji: '🏖️' },
    { value: 'MEDICAL', label: 'Medical Leave', emoji: '🏥' },
    { value: 'OD', label: 'On Duty (OD)', emoji: '📋' },
    { value: 'EARNED', label: 'Earned Leave', emoji: '🏆' },
    { value: 'SICK', label: 'Sick Leave', emoji: '🤒' },
  ];

  return (
    <div className="p-6">
      <div className="flex items-center justify-between mb-6">
        <h2 className="text-xl font-bold text-gray-900">Apply for Leave</h2>
        <button
          onClick={onCancel}
          className="p-2 hover:bg-gray-100 rounded-lg transition-colors"
        >
          <HiX className="w-5 h-5 text-gray-500" />
        </button>
      </div>

      <form onSubmit={handleSubmit} className="space-y-4">
        {/* Leave Type */}
        <div>
          <label className="label">Leave Type *</label>
          <select
            name="leaveType"
            value={formData.leaveType}
            onChange={handleChange}
            className="input-field"
            required
          >
            <option value="">Select leave type</option>
            {leaveTypes.map((type) => (
              <option key={type.value} value={type.value}>
                {type.emoji} {type.label}
              </option>
            ))}
          </select>
        </div>

        {/* Date Range */}
        <div className="grid grid-cols-2 gap-4">
          <div>
            <label className="label">Start Date *</label>
            <input
              type="date"
              name="startDate"
              value={formData.startDate}
              onChange={handleChange}
              min={dayjs().format('YYYY-MM-DD')}
              className="input-field"
              required
            />
          </div>
          <div>
            <label className="label">End Date *</label>
            <input
              type="date"
              name="endDate"
              value={formData.endDate}
              onChange={handleChange}
              min={formData.startDate || dayjs().format('YYYY-MM-DD')}
              className="input-field"
              required
            />
          </div>
        </div>

        {/* Duration Display */}
        {formData.startDate && formData.endDate && (
          <div className="bg-primary-50 rounded-lg p-3 text-sm text-primary-700">
            Duration: {dayjs(formData.endDate).diff(dayjs(formData.startDate), 'day') + 1} day(s)
          </div>
        )}

        {/* Reason */}
        <div>
          <label className="label">Reason *</label>
          <textarea
            name="reason"
            value={formData.reason}
            onChange={handleChange}
            rows={3}
            className="input-field resize-none"
            placeholder="Please provide a reason for your leave request..."
            required
          />
        </div>

        {/* Actions */}
        <div className="flex gap-3 pt-2">
          <button type="button" onClick={onCancel} className="btn-secondary flex-1">
            Cancel
          </button>
          <button type="submit" className="btn-primary flex-1">
            Submit Request
          </button>
        </div>
      </form>
    </div>
  );
}
