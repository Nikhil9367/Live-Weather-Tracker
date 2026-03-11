document.addEventListener('DOMContentLoaded', () => {
    const cityInput = document.getElementById('cityInput');
    const searchBtn = document.getElementById('searchBtn');
    const weatherCard = document.getElementById('weatherCard');
    const loader = document.getElementById('loader');
    const errorMsg = document.getElementById('errorMsg');
    const forecastContainer = document.getElementById('forecastContainer');

    // UI Elements
    const elCityName = document.getElementById('cityName');
    const elCondition = document.getElementById('conditionBadge');
    const elTemp = document.getElementById('temperature');
    const elDesc = document.getElementById('weatherDesc');
    const elFeelsLike = document.getElementById('feelsLike');
    const elHumidity = document.getElementById('humidity');
    const elWindSpeed = document.getElementById('windSpeed');
    const elPressure = document.getElementById('pressure');

    const API_PORT = 8080; // Assuming server runs on the same machine locally

    const fetchWeather = async (city) => {
        if (!city.trim()) return;

        // Reset UI
        weatherCard.classList.add('hidden');
        errorMsg.classList.add('hidden');
        loader.classList.remove('hidden');

        try {
            // Calling our Java Backend API for current weather
            const currentResponse = await fetch(`http://localhost:${API_PORT}/api/weather?city=${encodeURIComponent(city)}`);
            if (!currentResponse.ok) throw new Error('Network response was not ok');
            const currentData = await currentResponse.json();

            if (currentData.error || currentData.cod == "404" || currentData.cod == "400") {
                showError(currentData.error || currentData.message || "City not found.");
                return;
            }

            // Fetch forecast data
            let forecastData = null;
            try {
                const forecastResponse = await fetch(`http://localhost:${API_PORT}/api/forecast?city=${encodeURIComponent(city)}`);
                if (forecastResponse.ok) {
                    forecastData = await forecastResponse.json();
                }
            } catch (e) {
                console.error("Could not fetch forecast", e);
            }

            // Fetch Cultural/Landmark Image from Wikimedia Commons
            try {
                const safeCity = currentData.name || city;
                // Query Wikimedia Commons specifically for images (namespace 6) matching the city and "landmark" to avoid flags
                const wikiResponse = await fetch(`https://commons.wikimedia.org/w/api.php?action=query&generator=search&gsrnamespace=6&gsrsearch=${encodeURIComponent(safeCity)}%20landmark&gsrlimit=1&prop=imageinfo&iiprop=url&format=json&origin=*`);

                if (wikiResponse.ok) {
                    const wikiData = await wikiResponse.json();
                    const pages = wikiData.query?.pages;
                    if (pages) {
                        const pageId = Object.keys(pages)[0];
                        // Get the imageinfo URL
                        const imageUrl = pages[pageId]?.imageinfo?.[0]?.url;
                        if (imageUrl) {
                            document.body.style.backgroundImage = `url('${imageUrl}')`;
                        } else {
                            document.body.style.backgroundImage = 'none';
                        }
                    } else {
                        // Fallback if "landmark" returns nothing, try generic city search
                        const fallbackResponse = await fetch(`https://commons.wikimedia.org/w/api.php?action=query&generator=search&gsrnamespace=6&gsrsearch=${encodeURIComponent(safeCity)}%20cityscape&gsrlimit=1&prop=imageinfo&iiprop=url&format=json&origin=*`);
                        if (fallbackResponse.ok) {
                            const fallData = await fallbackResponse.json();
                            const fallPages = fallData.query?.pages;
                            if (fallPages) {
                                const pId = Object.keys(fallPages)[0];
                                const iUrl = fallPages[pId]?.imageinfo?.[0]?.url;
                                if (iUrl) {
                                    document.body.style.backgroundImage = `url('${iUrl}')`;
                                    return; // Exit early if fallback is found
                                }
                            }
                        }

                        document.body.style.backgroundImage = 'none';
                    }
                }
            } catch (e) {
                console.error("Could not fetch city image", e);
                document.body.style.backgroundImage = 'none';
            }

            updateUI(currentData, forecastData);
        } catch (error) {
            console.error('Error fetching weather:', error);
            showError("Could not connect to the weather server. Is the Java app running?");
        } finally {
            loader.classList.add('hidden');
        }
    };

    const updateUI = (data, forecastData) => {
        // Parse data from OpenWeatherMap JSON format
        const cityName = `${data.name}, ${data.sys?.country || ''}`;
        const condition = data.weather[0]?.main || 'Unknown';
        const desc = data.weather[0]?.description || 'Unknown';
        const temp = Math.round(data.main?.temp || 0);
        const feelsLike = Math.round(data.main?.feels_like || 0);
        const humidity = data.main?.humidity || 0;
        const wind = data.wind?.speed || 0;
        const pressure = data.main?.pressure || 0;

        // Update elements
        elCityName.textContent = cityName;
        elCondition.textContent = condition;
        elTemp.textContent = temp;
        elDesc.textContent = desc;
        elFeelsLike.textContent = `${feelsLike}°`;
        elHumidity.textContent = `${humidity}%`;
        elWindSpeed.textContent = `${wind} m/s`;
        elPressure.textContent = `${pressure} hPa`;

        // Update badge color and dynamic background based on condition
        const conditionLower = condition.toLowerCase();
        let badgeColor = '#93c5fd'; // Default blue
        let bgStyle = { blob1: '#3b82f6', blob2: '#8b5cf6', blob3: '#ec4899' }; // default

        if (conditionLower.includes('clear')) {
            badgeColor = '#fde047';
            bgStyle = { blob1: '#f59e0b', blob2: '#fbbf24', blob3: '#ef4444' };
        } else if (conditionLower.includes('cloud')) {
            badgeColor = '#cbd5e1';
            bgStyle = { blob1: '#64748b', blob2: '#94a3b8', blob3: '#475569' };
        } else if (conditionLower.includes('rain') || conditionLower.includes('drizzle')) {
            badgeColor = '#60a5fa';
            bgStyle = { blob1: '#2563eb', blob2: '#3b82f6', blob3: '#1e40af' };
        } else if (conditionLower.includes('thunder')) {
            badgeColor = '#c084fc';
            bgStyle = { blob1: '#7e22ce', blob2: '#9333ea', blob3: '#4c1d95' };
        } else if (conditionLower.includes('snow')) {
            badgeColor = '#ffffff';
            bgStyle = { blob1: '#e0f2fe', blob2: '#bae6fd', blob3: '#7dd3fc' };
        }

        elCondition.style.color = badgeColor;
        document.querySelector('.blob-1').style.background = bgStyle.blob1;
        document.querySelector('.blob-2').style.background = bgStyle.blob2;
        document.querySelector('.blob-3').style.background = bgStyle.blob3;

        // Render Forecast
        if (forecastData && forecastData.list) {
            renderForecast(forecastData.list);
        } else {
            forecastContainer.innerHTML = '<p class="desc">Forecast unnavailable</p>';
        }

        // Show card with animation
        weatherCard.classList.remove('hidden');
    };

    const renderForecast = (list) => {
        forecastContainer.innerHTML = '';

        // OpenWeatherMap free forecast returns data every 3 hours (8 items per day). 
        // We will display the next 24-48 hours of data (e.g. 10-15 items) to create a nice timeline
        const timelineData = list.slice(0, 15);

        timelineData.forEach(day => {
            const date = new Date(day.dt_txt || (day.dt * 1000));
            // Format time (e.g., "11 PM")
            const timeString = date.toLocaleTimeString('en-US', { hour: 'numeric', hour12: true });
            // Format date (e.g., "Mar 12")
            const dateString = date.toLocaleDateString('en-US', { month: 'short', day: 'numeric' });

            const temp = Math.round(day.main.temp);
            const condition = day.weather[0].main;

            // simple icon mapping based on main condition
            let icon = '☁️';
            if (condition.includes('Clear')) icon = '☀️';
            else if (condition.includes('Rain')) icon = '🌧️';
            else if (condition.includes('Snow')) icon = '❄️';
            else if (condition.includes('Thunder')) icon = '⛈️';
            else if (condition.includes('Drizzle')) icon = '🌦️';

            const itemHTML = `
                <div class="forecast-item">
                    <div class="forecast-time">${timeString}</div>
                    <div class="forecast-icon">${icon}</div>
                    <div class="forecast-temp">${temp}°</div>
                    <div class="forecast-date">${dateString}</div>
                </div>
            `;
            forecastContainer.insertAdjacentHTML('beforeend', itemHTML);
        });
    }

    const showError = (message) => {
        errorMsg.textContent = message;
        errorMsg.classList.remove('hidden');
    };

    // Event Listeners
    searchBtn.addEventListener('click', () => {
        fetchWeather(cityInput.value);
    });

    cityInput.addEventListener('keypress', (e) => {
        if (e.key === 'Enter') {
            fetchWeather(cityInput.value);
            cityInput.blur(); // Dismiss keyboard on mobile
        }
    });

    // Optional: Fetch default city on load
    // fetchWeather('London');
});
