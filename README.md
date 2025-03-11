# Artificial_Intelligence_Project

## Project Description
This project is designed for automatic transcription of audio files and generating summaries of the obtained text. It uses the OpenAI Whisper model for speech-to-text conversion and the Transformers library for summary generation.

## System Requirements
- Python 3.8 or newer
- FFmpeg (required for audio file processing)

## Installation

### 1. FFmpeg Installation
FFmpeg is essential for processing audio files with Whisper:

#### Windows
1. Download FFmpeg from [ffmpeg.org](https://ffmpeg.org/download.html) or [gyan.dev](https://www.gyan.dev/ffmpeg/builds/)
2. Extract the archive to a folder, e.g., `C:\ffmpeg`
3. Add the path to the FFmpeg bin folder to the PATH environment variable:
   - Open Windows Settings → System → About → Advanced system settings
   - Click "Environment Variables"
   - Edit the "Path" variable
   - Add the path to the FFmpeg bin folder (e.g., `C:\ffmpeg\bin`)
   - Confirm all dialog windows

#### Linux
```bash
sudo apt update
sudo apt install ffmpeg
```

### 2. Installing Required Python Libraries
```bash
pip install openai-whisper transformers torch
```

## How It Works
1. The Whisper model processes the provided audio file and converts speech to text
2. The obtained text is displayed in the console
3. The Transformers model generates a concise summary of the text

## Configuration Options
- Whisper model sizes:
  - `tiny`: Smallest model, fastest but least accurate
  - `base`: Good compromise between speed and accuracy
  - `small`: Higher accuracy, requires more resources
  - `medium`: High accuracy, slower
  - `large`: Highest accuracy, slowest

- Summary options:
  - `max_length`: Maximum length of the summary
  - `min_length`: Minimum length of the summary
  - `do_sample`: Whether the summary should include random elements
