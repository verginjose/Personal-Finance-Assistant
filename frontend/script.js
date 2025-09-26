// Global variables
let currentUser = null;
let authToken = null;
let userId = null;
let categoryChart = null;
let timelineChart = null;

// API Configuration
const API_BASE_URL = 'http://localhost:8080/api';

// DOM Elements
const loginScreen = document.getElementById('loginScreen');
const dashboardScreen = document.getElementById('dashboardScreen');
const loginForm = document.getElementById('loginForm');
const logoutBtn = document.getElementById('logoutBtn');
const navTabs = document.querySelectorAll('.nav-tab');
const tabContents = document.querySelectorAll('.tab-content');
const entryForm = document.getElementById('entryForm');
const billUploadForm = document.getElementById('billUploadForm');
const loadingOverlay = document.getElementById('loadingOverlay');

// Initialize the application
document.addEventListener('DOMContentLoaded', function() {
    initializeApp();
    setupEventListeners();
    checkAuthStatus();
});

function initializeApp() {
    // Check if user is already logged in
    const savedToken = localStorage.getItem('authToken');
    const savedUserId = localStorage.getItem('userId');
    const savedUserEmail = localStorage.getItem('userEmail');
    
    if (savedToken && savedUserId && savedUserEmail) {
        authToken = savedToken;
        userId = savedUserId;
        currentUser = { email: savedUserEmail };
        showDashboard();
    }
}

function setupEventListeners() {
    // Login form
    loginForm.addEventListener('submit', handleLogin);
    
    // Logout button
    logoutBtn.addEventListener('click', handleLogout);
    
    // Navigation tabs
    navTabs.forEach(tab => {
        tab.addEventListener('click', () => switchTab(tab.dataset.tab));
    });
    
    // Entry form
    entryForm.addEventListener('submit', handleEntrySubmit);
    
    // Transaction type change
    document.getElementById('entryType').addEventListener('change', handleTransactionTypeChange);
    
    // Bill upload form
    billUploadForm.addEventListener('submit', handleBillUpload);
    
    // OCR confirm button
    document.getElementById('confirmOcrEntry').addEventListener('click', handleOcrConfirm);
    
    // Analytics filters
    document.getElementById('applyFilters').addEventListener('click', loadAnalytics);
    
    // Set default dates for analytics
    setDefaultDates();
}

function checkAuthStatus() {
    if (authToken) {
        validateToken();
    }
}

async function validateToken() {
    try {
        const response = await fetch(`${API_BASE_URL}/auth/validate`, {
            method: 'GET',
            headers: {
                'Authorization': `Bearer ${authToken}`
            }
        });
        
        if (!response.ok) {
            throw new Error('Token validation failed');
        }
        
        showDashboard();
    } catch (error) {
        console.error('Token validation failed:', error);
        handleLogout();
    }
}

async function handleLogin(e) {
    e.preventDefault();
    showLoading();
    
    const formData = new FormData(loginForm);
    const loginData = {
        email: formData.get('email'),
        password: formData.get('password'),
        role: formData.get('role')
    };
    
    try {
        const response = await fetch(`${API_BASE_URL}/auth/login`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify(loginData)
        });
        
        if (!response.ok) {
            const errorData = await response.text();
            throw new Error(errorData || 'Login failed');
        }
        
        const data = await response.json();
        authToken = data.token;
        userId = data.userId;
        currentUser = { email: loginData.email };
        
        // Save to localStorage
        localStorage.setItem('authToken', authToken);
        localStorage.setItem('userId', userId);
        localStorage.setItem('userEmail', loginData.email);
        
        showDashboard();
        hideLoading();
        
    } catch (error) {
        console.error('Login error:', error);
        showError('loginError', error.message);
        hideLoading();
    }
}

function handleLogout() {
    authToken = null;
    userId = null;
    currentUser = null;
    
    // Clear localStorage
    localStorage.removeItem('authToken');
    localStorage.removeItem('userId');
    localStorage.removeItem('userEmail');
    
    // Reset forms
    loginForm.reset();
    entryForm.reset();
    billUploadForm.reset();
    
    // Clear error messages
    clearMessages();
    
    // Show login screen
    showLogin();
}

function showLogin() {
    loginScreen.classList.add('active');
    dashboardScreen.classList.remove('active');
}

function showDashboard() {
    loginScreen.classList.remove('active');
    dashboardScreen.classList.add('active');
    
    // Update user info
    document.getElementById('userEmail').textContent = currentUser.email;
    
    // Load dashboard data
    loadDashboardData();
}

function switchTab(tabName) {
    // Update nav tabs
    navTabs.forEach(tab => {
        tab.classList.remove('active');
        if (tab.dataset.tab === tabName) {
            tab.classList.add('active');
        }
    });
    
    // Update tab contents
    tabContents.forEach(content => {
        content.classList.remove('active');
        if (content.id === tabName) {
            content.classList.add('active');
        }
    });
    
    // Load specific tab data
    if (tabName === 'analytics') {
        loadAnalytics();
    }
}

async function loadDashboardData() {
    try {
        // Load comprehensive data for dashboard
        const response = await fetch(`${API_BASE_URL}/analytics/comprehensive?userId=${userId}`, {
            headers: {
                'Authorization': `Bearer ${authToken}`
            }
        });
        
        if (!response.ok) {
            throw new Error('Failed to load dashboard data');
        }
        
        const data = await response.json();
        
        // Display the data
        displayQuickStats(data);
        displayRecentTransactions(data.recentTransactions || []);
        
    } catch (error) {
        console.error('Error loading dashboard data:', error);
    }
}


function displayRecentTransactions(transactions) {
    const container = document.getElementById('recentTransactions');
    
    if (transactions.length === 0) {
        container.innerHTML = '<p>No transactions found</p>';
        return;
    }
    
    const html = transactions.slice(0, 5).map(transaction => `
        <div class="transaction-item">
            <div class="transaction-info">
                <div class="transaction-name">${transaction.name}</div>
                <div class="transaction-category">${transaction.expenseCategory || transaction.incomeCategory || 'N/A'}</div>
            </div>
            <div class="transaction-amount ${transaction.type.toLowerCase()}">
                ${transaction.type === 'EXPENSE' ? '-' : '+'}₹${transaction.amount}
            </div>
        </div>
    `).join('');
    
    container.innerHTML = html;
}


function displayQuickStats(data) {
    // Use the correct field names from backend response
    const totalIncome = data.totalIncome || data.totalincome || 0;
    const totalExpenses = data.totalExpenses || data.totalExpense || 0;
    const netBalance = totalIncome - totalExpenses;
    
    document.getElementById('totalIncome').textContent = `₹${totalIncome.toLocaleString()}`;
    document.getElementById('totalExpenses').textContent = `₹${totalExpenses.toLocaleString()}`;
    document.getElementById('netBalance').textContent = `₹${netBalance.toLocaleString()}`;
    document.getElementById('netBalance').className = `stat-value ${netBalance >= 0 ? 'income' : 'expense'}`;
}

function handleTransactionTypeChange() {
    const type = document.getElementById('entryType').value;
    const categorySelect = document.getElementById('entryCategory');
    const categoryLabel = document.getElementById('categoryLabel');
    
    // Clear existing options
    categorySelect.innerHTML = '<option value="">Select Category</option>';
    
    if (type === 'INCOME') {
        categoryLabel.textContent = 'Income Category *';
        const incomeCategories = [
            'SALARY', 'BUSINESS', 'INVESTMENTS', 'GIFTS', 
            'FREELANCE', 'RENTAL_INCOME', 'INTEREST', 'OTHERS'
        ];
        incomeCategories.forEach(category => {
            const option = document.createElement('option');
            option.value = category;
            option.textContent = category.replace('_', ' ');
            categorySelect.appendChild(option);
        });
    } else if (type === 'EXPENSE') {
        categoryLabel.textContent = 'Expense Category *';
        const expenseCategories = [
            'FOOD_AND_DINING', 'TRANSPORTATION', 'SHOPPING', 
            'ENTERTAINMENT', 'BILLS_AND_UTILITIES', 'HEALTHCARE', 
            'TRAVEL', 'EDUCATION', 'OTHERS'
        ];
        expenseCategories.forEach(category => {
            const option = document.createElement('option');
            option.value = category;
            option.textContent = category.replace('_', ' ');
            categorySelect.appendChild(option);
        });
    }
}

async function handleEntrySubmit(e) {
    e.preventDefault();
    showLoading();
    
    const formData = new FormData(entryForm);
    const entryData = {
        userId: userId,
        name: formData.get('name'),
        amount: parseFloat(formData.get('amount')),
        type: formData.get('type'),
        currency: formData.get('currency'),
        description: formData.get('description')
    };
    
    // Add appropriate category
    if (entryData.type === 'INCOME') {
        entryData.incomeCategory = formData.get('category');
    } else {
        entryData.expenseCategory = formData.get('category');
    }
    
    try {
        const response = await fetch(`${API_BASE_URL}/upsert/create`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Authorization': `Bearer ${authToken}`
            },
            body: JSON.stringify(entryData)
        });
        
        if (!response.ok) {
            const errorData = await response.text();
            throw new Error(errorData || 'Failed to create entry');
        }
        
        showSuccess('entrySuccess', 'Entry created successfully!');
        entryForm.reset();
        hideLoading();
        
        // Reload dashboard data
        loadDashboardData();
        
    } catch (error) {
        console.error('Entry creation error:', error);
        showError('entryError', error.message);
        hideLoading();
    }
}

async function handleBillUpload(e) {
    e.preventDefault();
    showLoading();
    
    const fileInput = document.getElementById('billFile');
    const file = fileInput.files[0];
    
    if (!file) {
        showError('uploadError', 'Please select a file');
        hideLoading();
        return;
    }
    
    const formData = new FormData();
    formData.append('file', file);
    
    try {
        const response = await fetch(`${API_BASE_URL}/bill/process/${userId}`, {
            method: 'POST',
            headers: {
                'Authorization': `Bearer ${authToken}`
            },
            body: formData
        });
        
        if (!response.ok) {
            const errorData = await response.text();
            throw new Error(errorData || 'Failed to process bill');
        }
        
        const data = await response.json();
        displayOcrResults(data);
        showSuccess('uploadSuccess', 'Bill processed successfully!');
        hideLoading();
        
    } catch (error) {
        console.error('Bill upload error:', error);
        showError('uploadError', error.message);
        hideLoading();
    }
}
function displayOcrResults(data) {
    document.getElementById('ocrName').value = data.name || '';
    document.getElementById('ocrAmount').value = data.amount || '';
    
    // Fix: Ensure the type is in the correct format expected by backend
    let transactionType = data.type || '';
    if (transactionType.toLowerCase() === 'expense') {
        transactionType = 'EXPENSE';
    } else if (transactionType.toLowerCase() === 'income') {
        transactionType = 'INCOME';
    }
    document.getElementById('ocrType').value = transactionType;
    
    document.getElementById('ocrCategory').value = data.expenseCategory || data.incomeCategory || '';
    document.getElementById('ocrCurrency').value = data.currency || 'INR';
    document.getElementById('ocrDescription').value = data.description || '';
    
    document.getElementById('ocrResults').style.display = 'block';
}

async function handleOcrConfirm() {
    const typeValue = document.getElementById('ocrType').value;
    
    // Validate the type before sending
    if (typeValue !== 'EXPENSE' && typeValue !== 'INCOME') {
        showError('uploadError', 'Please select a valid transaction type (EXPENSE or INCOME)');
        return;
    }
    
    const ocrData = {
        userId: userId,
        name: document.getElementById('ocrName').value,
        amount: parseFloat(document.getElementById('ocrAmount').value),
        type: typeValue,
        currency: document.getElementById('ocrCurrency').value,
        description: document.getElementById('ocrDescription').value
    };
    
    // Add appropriate category
    const category = document.getElementById('ocrCategory').value;
    if (ocrData.type === 'INCOME') {
        ocrData.incomeCategory = category;
    } else {
        ocrData.expenseCategory = category;
    }
    
    try {
        const response = await fetch(`${API_BASE_URL}/upsert/create`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Authorization': `Bearer ${authToken}`
            },
            body: JSON.stringify(ocrData)
        });
        
        if (!response.ok) {
            const errorData = await response.text();
            throw new Error(errorData || 'Failed to create entry');
        }
        
        showSuccess('uploadSuccess', 'Entry created from bill successfully!');
        document.getElementById('ocrResults').style.display = 'none';
        billUploadForm.reset();
        
        // Reload dashboard data
        loadDashboardData();
        
    } catch (error) {
        console.error('OCR entry creation error:', error);
        showError('uploadError', error.message);
    }
}
async function loadAnalytics() {
    showLoading();
    
    try {
        const filters = getAnalyticsFilters();
        
        // Load comprehensive data first
        const response = await fetch(`${API_BASE_URL}/analytics/comprehensive?${filters}`, {
            headers: {
                'Authorization': `Bearer ${authToken}`
            }
        });
        
        if (!response.ok) {
            throw new Error('Failed to load analytics data');
        }
        
        const data = await response.json();
        console.log('Analytics data received:', data);
        
        // Use only real data from backend - no dummy data
        renderCategoryChart(data);
        renderTimelineChart(data);
        displayComprehensiveStats(data);
        
        hideLoading();
    } catch (error) {
        console.error('Error loading analytics:', error);
        hideLoading();
    }
}

function getAnalyticsFilters() {
    const type = document.getElementById('analyticsType').value;
    const timelineType = document.getElementById('timelineType').value;
    const startDate = document.getElementById('startDate').value;
    const endDate = document.getElementById('endDate').value;
    
    let filters = `userId=${userId}`;
    
    if (type) filters += `&transactionFilter=${type}`;
    if (timelineType) filters += `&timelineType=${timelineType}`;
    if (startDate) filters += `&startDate=${startDate}T00:00:00`;
    if (endDate) filters += `&endDate=${endDate}T23:59:59`;
    
    return filters;
}


function renderCategoryChart(data) {
    const ctx = document.getElementById('categoryChart').getContext('2d');
    
    if (categoryChart) {
        categoryChart.destroy();
    }
    
    console.log('Rendering category chart with data:', data);
    
    // Handle the actual backend data format - use only real data
    let labels, values, colors;
    
    if (data.expenseByCategory && data.expenseByCategory.datasets) {
        // Backend sends data in this format
        labels = data.expenseByCategory.labels || [];
        values = data.expenseByCategory.datasets[0].data || [];
        colors = data.expenseByCategory.datasets[0].backgroundColor || generateColors(labels.length);
    } else if (data.expenseByCategory) {
        // Fallback format
        labels = data.expenseByCategory.labels || [];
        values = data.expenseByCategory.data || [];
        colors = generateColors(labels.length);
    } else if (Array.isArray(data)) {
        // If data is an array of objects
        labels = data.map(item => item.category || item.label);
        values = data.map(item => item.amount || item.value);
        colors = generateColors(labels.length);
    } else {
        console.error('Unknown category data format:', data);
        return;
    }
    
    // If no data, show a message
    if (labels.length === 0 || values.length === 0) {
        ctx.font = '16px Arial';
        ctx.fillStyle = '#666';
        ctx.textAlign = 'center';
        ctx.fillText('No expense data available', ctx.canvas.width / 2, ctx.canvas.height / 2);
        return;
    }
    
    console.log('Category chart data:', { labels, values, colors });
    
    categoryChart = new Chart(ctx, {
        type: 'pie',
        data: {
            labels: labels,
            datasets: [{
                data: values,
                backgroundColor: colors,
                borderWidth: 2,
                borderColor: '#fff'
            }]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            plugins: {
                legend: {
                    position: 'bottom',
                    labels: {
                        usePointStyle: true,
                        padding: 20
                    }
                },
                title: {
                    display: true,
                    text: 'Expense Categories',
                    font: {
                        size: 16,
                        weight: 'bold'
                    }
                }
            }
        }
    });
}


function renderTimelineChart(data) {
    const ctx = document.getElementById('timelineChart').getContext('2d');
    
    if (timelineChart) {
        timelineChart.destroy();
    }
    
    console.log('Rendering timeline chart with data:', data);
    
    // Handle the actual backend data format - use only real data
    let labels, incomeData, expenseData;
    
    if (data.timelineTrends && data.timelineTrends.datasets) {
        // Backend sends data in this format
        labels = data.timelineTrends.labels || [];
        incomeData = data.timelineTrends.datasets.find(d => d.label === 'Income')?.data || [];
        expenseData = data.timelineTrends.datasets.find(d => d.label === 'Expense')?.data || [];
    } else if (data.timelineTrends) {
        // Fallback format
        labels = data.timelineTrends.labels || [];
        incomeData = data.timelineTrends.datasets?.find(d => d.label === 'Income')?.data || [];
        expenseData = data.timelineTrends.datasets?.find(d => d.label === 'Expenses')?.data || [];
    } else if (Array.isArray(data)) {
        // If data is an array of objects
        labels = data.map(item => item.period || item.label);
        incomeData = data.map(item => item.income || 0);
        expenseData = data.map(item => item.expense || 0);
    } else {
        console.error('Unknown timeline data format:', data);
        return;
    }
    
    // If no data, show a message
    if (labels.length === 0) {
        ctx.font = '16px Arial';
        ctx.fillStyle = '#666';
        ctx.textAlign = 'center';
        ctx.fillText('No timeline data available', ctx.canvas.width / 2, ctx.canvas.height / 2);
        return;
    }
    
    console.log('Timeline chart data:', { labels, incomeData, expenseData });
    
    timelineChart = new Chart(ctx, {
        type: 'line',
        data: {
            labels: labels,
            datasets: [{
                label: 'Income',
                data: incomeData,
                borderColor: '#28a745',
                backgroundColor: 'rgba(40, 167, 69, 0.1)',
                tension: 0.4,
                fill: true,
                pointRadius: 6,
                pointHoverRadius: 8
            }, {
                label: 'Expenses',
                data: expenseData,
                borderColor: '#dc3545',
                backgroundColor: 'rgba(220, 53, 69, 0.1)',
                tension: 0.4,
                fill: true,
                pointRadius: 6,
                pointHoverRadius: 8
            }]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            plugins: {
                title: {
                    display: true,
                    text: 'Monthly Transaction Trends',
                    font: {
                        size: 16,
                        weight: 'bold'
                    }
                },
                legend: {
                    position: 'top',
                    labels: {
                        usePointStyle: true,
                        padding: 20
                    }
                }
            },
            scales: {
                y: {
                    beginAtZero: true,
                    grid: {
                        color: 'rgba(0,0,0,0.1)'
                    }
                },
                x: {
                    grid: {
                        color: 'rgba(0,0,0,0.1)'
                    }
                }
            }
        }
    });
}


function displayComprehensiveStats(data) {
    const container = document.getElementById('comprehensiveStats');
    
    // Handle different data formats from backend
    const totalIncome = data.totalIncome || data.totalincome || 0;
    const totalExpenses = data.totalExpenses || data.totalExpense || 0;
    const netBalance = totalIncome - totalExpenses;
    const transactionCount = data.transactionCount || data.transactioncount || 0;
    
    const stats = [
        { label: 'Total Income', value: `₹${totalIncome.toLocaleString()}`, class: 'income' },
        { label: 'Total Expenses', value: `₹${totalExpenses.toLocaleString()}`, class: 'expense' },
        { label: 'Net Balance', value: `₹${netBalance.toLocaleString()}`, class: netBalance >= 0 ? 'income' : 'expense' },
        { label: 'Transaction Count', value: transactionCount.toString(), class: 'count' }
    ];
    
    const html = stats.map(stat => `
        <div class="stat-item">
            <span class="stat-label">${stat.label}</span>
            <span class="stat-value ${stat.class}">${stat.value}</span>
        </div>
    `).join('');
    
    container.innerHTML = html;
}

function setDefaultDates() {
    const today = new Date();
    const firstDay = new Date(today.getFullYear(), today.getMonth(), 1);
    const lastDay = new Date(today.getFullYear(), today.getMonth() + 1, 0);
    
    document.getElementById('startDate').value = firstDay.toISOString().split('T')[0];
    document.getElementById('endDate').value = lastDay.toISOString().split('T')[0];
}

function generateColors(count) {
    const colors = [
        '#667eea', '#764ba2', '#f093fb', '#f5576c',
        '#4facfe', '#00f2fe', '#43e97b', '#38f9d7',
        '#ffecd2', '#fcb69f', '#a8edea', '#fed6e3'
    ];
    
    return colors.slice(0, count);
}

function showLoading() {
    loadingOverlay.classList.add('active');
}

function hideLoading() {
    loadingOverlay.classList.remove('active');
}

function showError(elementId, message) {
    const errorElement = document.getElementById(elementId);
    errorElement.textContent = message;
    errorElement.style.display = 'block';
    
    // Auto-hide after 5 seconds
    setTimeout(() => {
        errorElement.style.display = 'none';
    }, 5000);
}

function showSuccess(elementId, message) {
    const successElement = document.getElementById(elementId);
    successElement.textContent = message;
    successElement.style.display = 'block';
    
    // Auto-hide after 3 seconds
    setTimeout(() => {
        successElement.style.display = 'none';
    }, 3000);
}

function clearMessages() {
    const messages = document.querySelectorAll('.error-message, .success-message');
    messages.forEach(msg => {
        msg.style.display = 'none';
        msg.textContent = '';
    });
}
