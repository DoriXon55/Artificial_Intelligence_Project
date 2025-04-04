document.addEventListener('DOMContentLoaded', function() {
    // Obsługa wyboru metody przetwarzania
    const methodRadios = document.querySelectorAll('input[name="processingMethod"]');
    const geminiOptions = document.getElementById('geminiOptions');
    const modelSelect = document.getElementById('geminiModel');

    document.getElementById("currentYear").textContent = new Date().getFullYear();

    // Pokaż/ukryj opcje Gemini w zależności od wybranej metody
    methodRadios.forEach(radio => {
        radio.addEventListener('change', function() {
            if (this.value === 'gemini') {
                geminiOptions.style.display = 'block';
            } else {
                geminiOptions.style.display = 'none';
            }
        });
    });

    // Zapisz preferencje użytkownika
    document.getElementById('savePreferences').addEventListener('click', function() {
        const method = document.querySelector('input[name="processingMethod"]:checked').value;
        const modelId = method === 'gemini' ? modelSelect.value : null;

        // Wysyłanie preferencji do backend'u
        fetch('/api/preferences', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({method, modelId})
        })
            .then(response => response.json())
            .then(data => {
                if (data.success) {
                    const savedMessage = document.getElementById('savedMessage');
                    savedMessage.style.display = 'block';
                    setTimeout(() => savedMessage.style.display = 'none', 3000);
                }
            })
            .catch(error => {
                console.error('Błąd zapisywania preferencji:', error);
                alert('Wystąpił błąd podczas zapisywania preferencji.');
            });
    });

    // Ładowanie wybranych modeli Gemini
    fetch('/api/models')
        .then(response => response.json())
        .then(data => {
            if (data.models && data.models.length > 0) {
                modelSelect.innerHTML = '';
                data.models.forEach(model => {
                    const modelName = model.name.split('/').pop();
                    const option = new Option(modelName, modelName);
                    modelSelect.add(option);
                });
            }
        })
        .catch(error => {
            console.error('Błąd ładowania modeli:', error);
        });

    // Funkcje kopiowania treści
    document.getElementById('copyTranscription')?.addEventListener('click', function() {
        copyTextToClipboard(document.getElementById('transcriptionContent').textContent);
        showCopySuccess(this);
    });

    document.getElementById('copySummary')?.addEventListener('click', function() {
        copyTextToClipboard(document.getElementById('summaryContent').textContent);
        showCopySuccess(this);
    });

    // Funkcje ukrywania/pokazywania treści
    document.getElementById('toggleTranscription')?.addEventListener('click', function() {
        toggleContentVisibility('transcriptionContent', this);
    });

    document.getElementById('toggleSummary')?.addEventListener('click', function() {
        toggleContentVisibility('summaryContent', this);
    });

    // Pomocnicze funkcje
    function copyTextToClipboard(text) {
        navigator.clipboard.writeText(text.trim())
            .catch(err => console.error('Błąd kopiowania tekstu:', err));
    }

    function showCopySuccess(button) {
        const originalText = button.innerHTML;
        button.innerHTML = '<i class="bi bi-check-circle me-1"></i> Skopiowano';
        setTimeout(() => {
            button.innerHTML = originalText;
        }, 2000);
    }

    function toggleContentVisibility(contentId, button) {
        const content = document.getElementById(contentId);
        if (content.style.display === 'none') {
            content.style.display = 'block';
            button.innerHTML = '<i class="bi bi-eye-slash me-1"></i> Ukryj';
        } else {
            content.style.display = 'none';
            button.innerHTML = '<i class="bi bi-eye me-1"></i> Pokaż';
        }
    }

    // Dodanie funkcji "drag and drop" dla pliku
    const fileUpload = document.querySelector('.file-upload');
    const fileInput = document.getElementById('file');

    ['dragenter', 'dragover', 'dragleave', 'drop'].forEach(eventName => {
        fileUpload.addEventListener(eventName, preventDefaults, false);
    });

    function preventDefaults(e) {
        e.preventDefault();
        e.stopPropagation();
    }

    ['dragenter', 'dragover'].forEach(eventName => {
        fileUpload.addEventListener(eventName, highlight, false);
    });

    ['dragleave', 'drop'].forEach(eventName => {
        fileUpload.addEventListener(eventName, unhighlight, false);
    });

    function highlight() {
        fileUpload.classList.add('border-primary', 'bg-light');
    }

    function unhighlight() {
        fileUpload.classList.remove('border-primary', 'bg-light');
    }

    fileUpload.addEventListener('drop', handleDrop, false);

    function handleDrop(e) {
        const dt = e.dataTransfer;
        const files = dt.files;
        fileInput.files = files;
    }
});