from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field, model_validator
from typing import List, Optional, Literal, Dict
from datetime import date
import uvicorn
import os
import google.generativeai as genai
import json
import requests

# --- 1. SETUP: Load API Key ---
try:
    # Use the environment variable "GOOGLE_API_KEY" for security
    api_key = os.environ.get("GOOGLE_API_KEY")
    if not api_key:
        raise ValueError("GOOGLE_API_KEY environment variable not set.")
    genai.configure(api_key=api_key)
except (KeyError, ValueError) as e:
    raise RuntimeError(f"API Key configuration error: {e}")


# --- 2. Pydantic Models for Financial Documents ---
ExpenseCategory = Literal["FOOD_AND_DINING", "TRANSPORTATION", "SHOPPING", "ENTERTAINMENT", "BILLS_AND_UTILITIES", "HEALTHCARE", "TRAVEL", "EDUCATION", "OTHERS"]
IncomeCategory = Literal["SALARY", "BUSINESS", "INVESTMENTS", "GIFTS", "FREELANCE", "RENTAL_INCOME", "INTEREST", "OTHERS"]

class LineItem(BaseModel):
    description: str = Field(..., description="Description of the item purchased.")
    quantity: Optional[float] = Field(1.0, description="Quantity of the item, defaults to 1.")
    total_price: float = Field(..., description="Total price for this line item in the original currency.")

class FinancialDocument(BaseModel):
    userId: str = Field(..., description="The unique identifier for the user.")
    name: str = Field(..., description="The name of the vendor, source of income, or transaction entity.")
    amount: float = Field(..., description="The total amount of the transaction in the original currency.")
    type: Literal["Expense", "Income", "Rent", "Allowance", "Other"] = Field(..., description="The primary type of the transaction.")
    expenseCategory: Optional[ExpenseCategory] = Field(None, description="The specific category if the transaction is an Expense.")
    incomeCategory: Optional[IncomeCategory] = Field(None, description="The specific category if the transaction is an Income.")
    currency: str = Field(..., description="The 3-letter currency code (e.g., INR, USD).")
    description: Optional[str] = Field(None, description="A general description of the transaction.")
    line_items: Optional[List[LineItem]] = Field(None, description="A list of items for detailed receipts.")

    @model_validator(mode='after')
    def check_categories(self) -> 'FinancialDocument':
        if self.type == 'Expense' and not self.expenseCategory:
            raise ValueError("expenseCategory must be set for Expense transactions.")
        if self.type == 'Expense' and self.incomeCategory:
            raise ValueError("incomeCategory must not be set for Expense transactions.")
        if self.type == 'Income' and not self.incomeCategory:
            raise ValueError("incomeCategory must be set for Income transactions.")
        if self.type == 'Income' and self.expenseCategory:
            raise ValueError("expenseCategory must not be set for Income transactions.")
        return self

class ProcessedFinancialDocument(FinancialDocument):
    total_amount_inr: float
    total_amount_usd: float
    exchange_rate_date: str

class DocumentInput(BaseModel):
    user_id: str
    raw_text: str = Field(..., min_length=10, description="The raw text extracted from a document.")


# --- 3. Helper Functions ---
def get_exchange_rates(base_currency: str) -> Dict[str, any]:
    """Gets exchange rates for USD and INR using the Frankfurter API."""
    if base_currency == "₹": base_currency = "INR"
    targets = "USD,INR"
    if base_currency.upper() == "USD": targets = "INR"
    if base_currency.upper() == "INR": targets = "USD"
    api_url = f"https://api.frankfurter.app/latest?from={base_currency.upper()}&to={targets}"
    try:
        response = requests.get(api_url, timeout=5)
        response.raise_for_status()
        data = response.json()
        rates = data.get("rates", {})
        # Ensure the base currency has a rate of 1.0
        if base_currency.upper() == "USD": rates["USD"] = 1.0
        if base_currency.upper() == "INR": rates["INR"] = 1.0
        return data # return full data to get date
    except requests.exceptions.RequestException as e:
        print(f"Error calling currency API: {e}. Returning default rates.")
        return {"rates": {"USD": 0, "INR": 0}, "date": str(date.today())}

def extract_financial_data_with_gemini(text: str, user_id: str) -> FinancialDocument:
    """Uses the Gemini model to extract financial data from text and combines it with the user_id."""
    model = genai.GenerativeModel('gemini-1.5-flash-latest')
    prompt = f"""
    You are an expert financial data entry assistant. Your task is to analyze the raw text from a financial document.

    **Crucial Instructions:**
    1.  Identify the transaction 'type'.
    2.  Based on the 'type', you MUST categorize it by choosing ONLY from the provided lists:
        - If 'type' is 'Expense', for 'expenseCategory' you MUST choose one of: {{list(ExpenseCategory.__args__)}}. 'incomeCategory' MUST be null.
        - If 'type' is 'Income', for 'incomeCategory' you MUST choose one of: {{list(IncomeCategory.__args__)}}. 'expenseCategory' MUST be null.
    3.  Identify the 'name' of the vendor or source.
    4.  Identify the total 'amount' and the 'currency' (use 3-letter ISO code like INR for ₹).
    5.  For `line_items`, each item MUST be an object with keys 'description', 'quantity', and 'total_price'.

    **Output Format:**
    Provide the output ONLY in the following JSON format. Do not include 'userId'.

    JSON Schema (excluding userId):
    { {k: v for k, v in FinancialDocument.model_json_schema()['properties'].items() if k != 'userId'} }

    ---
    Raw Text to Analyze:
    {text}
    ---
    """
    print(f"Sending request to Gemini API for user: {user_id}")
    response = None
    try:
        response = model.generate_content(prompt)
        print(response)
        # Clean the response to ensure it is valid JSON
        if response.startswith("```"):
            response=response.strip("`").replace("json","")
        clean_response = response
        llm_output = json.loads(clean_response)
        full_data = {"userId": user_id, **llm_output}
        validated_data = FinancialDocument(**full_data)
        print("Successfully extracted and validated financial data.")
        return validated_data
    except Exception as e:
        print(f"An unexpected error during Gemini API call or Pydantic validation: {e}")
        if response: print(f"Raw response text: {response.text}")
        raise HTTPException(status_code=500, detail=f"Failed to process text with LLM: {str(e)}")


# --- 4. FastAPI Application ---
app = FastAPI(
    title="Financial Document Processing Service",
    description="An API to extract, categorize, and convert financial data from raw text.",
    version="1.0.0"
)

@app.post("/process-document-convert", response_model=ProcessedFinancialDocument)
async def process_document_and_convert(data: DocumentInput):
    """
    Receives raw text, extracts structured data, converts currency, and returns the final data.
    """
    try:
        extracted_data = extract_financial_data_with_gemini(data.raw_text, data.user_id)

        exchange_data = get_exchange_rates(extracted_data.currency)
        rates = exchange_data.get("rates", {})
        rate_usd = rates.get("USD", 0)
        rate_inr = rates.get("INR", 0)

        original_amount = extracted_data.amount

        if extracted_data.currency.upper() == "USD":
            amount_usd, amount_inr = original_amount, original_amount * rate_inr
        elif extracted_data.currency.upper() == "INR":
            amount_inr, amount_usd = original_amount, original_amount * rate_usd
        else: # Convert from a third currency to both USD and INR
            amount_usd, amount_inr = original_amount * rate_usd, original_amount * rate_inr

        final_document = ProcessedFinancialDocument(
            **extracted_data.model_dump(),
            total_amount_inr=round(amount_inr, 2),
            total_amount_usd=round(amount_usd, 2),
            exchange_rate_date=exchange_data.get("date", str(date.today()))
        )
        return final_document
    except HTTPException as e:
        # Re-raise HTTPException to preserve status code and details
        raise e
    except Exception as e:
        print(f"An unexpected error occurred in the endpoint: {e}")
        raise HTTPException(status_code=400, detail=f"Invalid text data or processing error: {str(e)}")

# Health check endpoint
@app.get("/")
def read_root():
    return {"status": "ok"}

if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8010)
