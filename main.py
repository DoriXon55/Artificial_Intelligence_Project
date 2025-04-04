import whisper
import sys
import json
import os
from transformers import pipeline
import logging
import torch
import hashlib
import pickle
from pathlib import Path

# Konfiguracja logowania
logging.basicConfig(level=logging.INFO, stream=sys.stderr, format='%(message)s')


    
def get_file_hash(file_path):
    """Generuje hash pliku do identyfikacji w cache"""
    hasher = hashlib.md5()
    with open(file_path, 'rb') as f:
        buf = f.read(65536)  # Odczytuj po 64KB
        while len(buf) > 0:
            hasher.update(buf)
            buf = f.read(65536)
    return hasher.hexdigest()

def check_cache(file_path, cache_dir=".cache"):
    """Sprawdza czy wyniki dla pliku są w cache"""
    os.makedirs(cache_dir, exist_ok=True)
    file_hash = get_file_hash(file_path)
    cache_path = os.path.join(cache_dir, f"{file_hash}.pkl")

    if os.path.exists(cache_path):
        try:
            with open(cache_path, 'rb') as f:
                cached_result = pickle.load(f)
            logging.info(f"Using cached results for {file_path}")
            return cached_result
        except Exception as e:
            logging.warning(f"Failed to load cache: {e}")

    return None

def save_to_cache(result, file_path, cache_dir=".cache"):
    """Zapisuje wyniki do cache"""
    os.makedirs(cache_dir, exist_ok=True)
    file_hash = get_file_hash(file_path)
    cache_path = os.path.join(cache_dir, f"{file_hash}.pkl")

    try:
        with open(cache_path, 'wb') as f:
            pickle.dump(result, f)
        logging.info(f"Saved results to cache for {file_path}")
    except Exception as e:
        logging.warning(f"Failed to save cache: {e}")

def main():
    error_log_path = os.path.join(os.path.dirname(__file__), "error_log.txt")
    sys.stderr = open(error_log_path, "a")

    try:
        if len(sys.argv) != 2:
            print(json.dumps({"error": "Expected file path as argument"}))
            sys.exit(1)

        file_path = sys.argv[1]
        if not os.path.exists(file_path):
            print(json.dumps({"error": f"File not found: {file_path}"}))
            sys.exit(1)

        logging.info(f"Processing file: {file_path}")

        # Sprawdź cache
        cached_result = check_cache(file_path)
        if cached_result:
            print(json.dumps(cached_result))
            return

        # Wybór urządzenia
        device = "cuda" if torch.cuda.is_available() else "cpu"
        logging.info(f"Using device: {device}")

        # Ustalanie parametrów urządzenia dla lepszej wydajności
        if device == "cuda":
            torch.backends.cudnn.benchmark = True
            torch.set_float32_matmul_precision('high')

        # Ładowanie modeli
        logging.info("Loading Whisper model...")
        model = whisper.load_model("base", device=device)

        # Transkrypcja audio z FP16 dla szybszych obliczeń na GPU
        logging.info("Transcribing audio...")
        with torch.inference_mode():
            result = model.transcribe(file_path, fp16=(device == "cuda"))
        transcription = result["text"]
        logging.info("Transcription completed successfully")

        # Przygotowanie tekstu
        words = transcription.split()
        if len(words) > 600:
            beginning = " ".join(words[:100])
            middle = " ".join(words[len(words)//2-50:len(words)//2+50])
            end = " ".join(words[-100:])
            truncated_text = beginning + " ... " + middle + " ... " + end
        else:
            truncated_text = " ".join(words[:min(200, len(words))])

        # Podsumowanie tekstu
        logging.info("Summarizing text...")
        summarizer = pipeline("summarization", model="facebook/bart-large-cnn",
                             device=0 if device == "cuda" else -1)

        instruction = "Please summarize the following speech: "
        truncated_text_with_instruction = instruction + truncated_text
        summary = summarizer(truncated_text_with_instruction, max_length=100, min_length=30, do_sample=False)

        # Przygotowanie wyniku
        output = {
            "transcription": transcription,
            "summary": summary[0]["summary_text"],
        }

        # Zapis do cache
        save_to_cache(output, file_path)

        # Wydruk wyniku jako JSON
        print(json.dumps(output))

    except Exception as e:
        import traceback
        error_message = traceback.format_exc()
        logging.error(f"Error: {str(e)}\n{error_message}")
        print(json.dumps({"error": str(e)}))
        sys.exit(1)

if __name__ == "__main__":
    main()