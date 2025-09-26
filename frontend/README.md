# Typeface India - Frontend Application

A comprehensive financial management frontend built with vanilla HTML, CSS, and JavaScript that integrates with your existing backend microservices.

## Features

### 🔐 Authentication
- Secure login with JWT token management
- Automatic token validation
- Persistent login sessions
- Role-based access (USER/ADMIN)

### 💰 Financial Management
- **Manual Entry**: Create income and expense entries with detailed categorization
- **Bill Upload**: Upload images or PDFs for automatic OCR processing
- **Real-time Validation**: Form validation with user-friendly error messages

### 📊 Analytics Dashboard
- **Interactive Charts**: Pie charts for category distribution and line charts for timeline analysis
- **Comprehensive Statistics**: Total income, expenses, net balance, and transaction counts
- **Advanced Filtering**: Filter by transaction type, timeline, and date ranges
- **Recent Transactions**: Quick view of latest financial activities

### 🎨 Modern UI/UX
- Responsive design that works on all devices
- Clean, professional interface with smooth animations
- Intuitive navigation with tab-based layout
- Loading states and user feedback

## File Structure

```
frontend/
├── index.html          # Main HTML structure
├── styles.css          # Complete CSS styling
├── script.js           # JavaScript functionality
└── README.md           # This documentation
```

## API Integration

The frontend integrates with the following backend services:

### Authentication Service (`/api/auth`)
- `POST /login` - User authentication
- `GET /validate` - Token validation

### Upsert Service (`/api/upsert`)
- `POST /create` - Create new financial entries

### Analytics Service (`/api/analytics`)
- `GET /comprehensive` - Get comprehensive financial statistics
- `GET /category-pie-chart` - Get category distribution data
- `GET /timeline-chart` - Get timeline analysis data

### OCR Parser Service (`/api/bill`)
- `POST /process/{userId}` - Process bill images/PDFs with OCR

## Setup Instructions

1. **Start Backend Services**
   ```bash
   # Make sure all your microservices are running
   docker-compose up -d
   ```

2. **Start Frontend Server**
   ```bash
   # Navigate to frontend directory
   cd frontend
   
   # Start the development server (recommended)
   python server.py
   
   # Or on Windows
   start-server.bat
   ```

3. **Access the Application**
   - The server will automatically open your browser
   - Or manually navigate to `http://localhost:8001`
   - The development server includes CORS headers to prevent issues

## CORS Issues Fixed

The application now includes proper CORS configuration:
- **API Gateway**: Handles CORS for all routes
- **Individual Services**: Removed conflicting CORS annotations
- **Frontend Server**: Includes CORS headers for development

## Usage Guide

### Login
1. Use the test credentials:
   - Email: `test@example.com`
   - Password: `password123`
   - Role: `USER`

### Adding Manual Entries
1. Click on the "Add Entry" tab
2. Fill in the required fields:
   - Name: Transaction description
   - Amount: Financial amount
   - Type: Income or Expense
   - Category: Automatically populated based on type
   - Currency: INR, USD, or EUR
   - Description: Optional details
3. Click "Add Entry" to save

### Uploading Bills
1. Click on the "Upload Bill" tab
2. Select an image (PNG, JPG) or PDF file
3. Click "Process Bill" to extract information using OCR
4. Review the extracted data
5. Click "Confirm & Add Entry" to save

### Viewing Analytics
1. Click on the "Analytics" tab
2. Use filters to customize your view:
   - Transaction Type: All, Income, or Expense
   - Timeline: Daily, Monthly, or Yearly
   - Date Range: Custom start and end dates
3. View interactive charts and comprehensive statistics

## Technical Details

### Authentication Flow
- JWT tokens are stored in localStorage
- Automatic token validation on page load
- Secure API calls with Bearer token authentication

### Data Validation
- Client-side form validation
- Server-side error handling
- User-friendly error messages

### Chart Integration
- Chart.js library for interactive visualizations
- Responsive charts that adapt to screen size
- Real-time data updates

### File Upload
- Support for images (PNG, JPG) and PDF files
- Maximum file size: 10MB
- Multipart form data handling

## Browser Compatibility

- Chrome 60+
- Firefox 55+
- Safari 12+
- Edge 79+

## Security Features

- JWT token-based authentication
- Secure API communication
- Input validation and sanitization
- CORS handling for cross-origin requests

## Error Handling

- Comprehensive error messages for all operations
- Loading states for better user experience
- Graceful fallbacks for failed API calls
- Form validation with real-time feedback

## Customization

The application is built with vanilla technologies, making it easy to customize:

- **Styling**: Modify `styles.css` for visual changes
- **Functionality**: Update `script.js` for feature additions
- **API Endpoints**: Change the `API_BASE_URL` in `script.js`
- **Categories**: Update category lists in the JavaScript code

## Troubleshooting

### Common Issues

1. **CORS Errors**: Ensure your backend services have proper CORS configuration
2. **API Connection**: Verify that all microservices are running on the correct ports
3. **File Upload**: Check that the OCR service is properly configured
4. **Charts Not Loading**: Ensure Chart.js library is loaded correctly

### Debug Mode

Open browser developer tools (F12) to view:
- Console logs for debugging
- Network requests and responses
- Local storage contents

## Future Enhancements

Potential improvements for the application:
- Export functionality for reports
- Advanced filtering options
- Data visualization improvements
- Mobile app version
- Offline support
- Multi-language support

---

**Note**: This frontend is designed to work with your existing backend microservices. Make sure all services are running and properly configured before using the application.
