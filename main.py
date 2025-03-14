import whisper
import sys
import json
import os
from transformers import pipeline
import logging

# Konfiguracja logowania do stderr zamiast stdout
logging.basicConfig(level=logging.INFO, stream=sys.stderr, format='%(message)s')

def main():
    # Przekierowanie standardowego wyjścia błędów do pliku (opcjonalnie)
    error_log_path = os.path.join(os.path.dirname(__file__), "error_log.txt")
    sys.stderr = open(error_log_path, "a")

    try:
        # Pobranie ścieżki do pliku z argumentów wiersza poleceń
        if len(sys.argv) != 2:
            print(json.dumps({"error": "Expected file path as argument"}))
            sys.exit(1)

        file_path = sys.argv[1]

        # Sprawdzenie czy plik istnieje
        if not os.path.exists(file_path):
            print(json.dumps({"error": f"File not found: {file_path}"}))
            sys.exit(1)

        logging.info(f"Processing file: {file_path}")

        # Załadowanie modelu Whisper
        logging.info("Loading Whisper model...")
        model = whisper.load_model("base")

        # Transkrypcja pliku audio
        logging.info("Transcribing audio...")
        result = model.transcribe(file_path)
        transcription = result["text"]

        logging.info("Transcription completed successfully")

        # Przygotowanie tekstu do podsumowania
        text = transcription
        words = text.split()

        if len(words) > 600:
            beginning = " ".join(words[:100])
            middle = " ".join(words[len(words)//2-50:len(words)//2+50])
            end = " ".join(words[-100:])
            truncated_text = beginning + " ... " + middle + " ... " + end
        else:
            truncated_text = " ".join(words[:min(200, len(words))])

        # Podsumowanie tekstu
        logging.info("Summarizing text...")
        summarizer = pipeline("summarization", model="facebook/bart-large-cnn")

        # Dodanie instrukcji dla lepszego podsumowania
        instruction = "Please summarize the following speech into a few sentences: "
        truncated_text_with_instruction = instruction + truncated_text
        summary = summarizer(truncated_text_with_instruction, max_length=100, min_length=30, do_sample=False)

        # Przygotowanie wyniku w formacie JSON
        output = {
            "transcription": transcription,
            "summary": summary[0]["summary_text"]
        }

        # Wydruk wyniku jako JSON - TYLKO TO idzie na stdout
        print(json.dumps(output))

    except Exception as e:
        import traceback
        error_message = traceback.format_exc()
        logging.error(f"Error: {str(e)}\n{error_message}")
        print(json.dumps({"error": str(e)}))
        sys.exit(1)

if __name__ == "__main__":
    main()